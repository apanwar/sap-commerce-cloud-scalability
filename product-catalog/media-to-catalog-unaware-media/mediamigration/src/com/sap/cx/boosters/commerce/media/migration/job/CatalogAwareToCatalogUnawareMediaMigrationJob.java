package com.sap.cx.boosters.commerce.media.migration.job;

import com.sap.cx.boosters.commerce.media.migration.model.CatalogAwareToCatalogUnawareMediaMigrationCronJobModel;
import com.sap.cx.boosters.commerce.media.migration.processor.MediaMigrationProcessor;
import com.sap.cx.boosters.commerce.media.migration.runner.MediaMigrationWorker;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.core.servicelayer.data.PaginationData;
import de.hybris.platform.core.servicelayer.data.SearchPageData;
import de.hybris.platform.cronjob.enums.CronJobResult;
import de.hybris.platform.cronjob.enums.CronJobStatus;
import de.hybris.platform.servicelayer.cronjob.AbstractJobPerformable;
import de.hybris.platform.servicelayer.cronjob.PerformResult;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.paginated.PaginatedFlexibleSearchParameter;
import de.hybris.platform.servicelayer.search.paginated.PaginatedFlexibleSearchService;
import org.apache.commons.lang.BooleanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

public class CatalogAwareToCatalogUnawareMediaMigrationJob extends AbstractJobPerformable<CatalogAwareToCatalogUnawareMediaMigrationCronJobModel> {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogAwareToCatalogUnawareMediaMigrationJob.class);

    private static final String QUERY = "SELECT {" + ProductModel.PK + "} FROM {" + ProductModel._TYPECODE + "} WHERE {" + ProductModel.CATALOGVERSION + "} =?" + ProductModel.CATALOGVERSION + " ORDER BY { " + ProductModel.CREATIONTIME + "}";

    private MediaMigrationProcessor mediaMigrationProcessor;

    private PaginatedFlexibleSearchService paginatedFlexibleSearchService;

    @Override
    public PerformResult perform(CatalogAwareToCatalogUnawareMediaMigrationCronJobModel cronJob) {

        var paginationData = new PaginationData();
        paginationData.setPageSize(cronJob.getBatchSize());
        paginationData.setCurrentPage(0);

        var searchPageData = new SearchPageData<ProductModel>();
        searchPageData.setPagination(paginationData);

        var fsq = new FlexibleSearchQuery(QUERY);
        fsq.addQueryParameter(ProductModel.CATALOGVERSION, cronJob.getReferenceCatalogVersion());
        fsq.setDisableSearchRestrictions(true);

        var pfsParam = new PaginatedFlexibleSearchParameter();
        pfsParam.setFlexibleSearchQuery(fsq);
        pfsParam.setSearchPageData(searchPageData);

        ExecutorService executorService = Executors.newFixedThreadPool(cronJob.getMigrationWorkers());
        List<Future<Void>> futures;

        AtomicReference<CronJobResult> result = new AtomicReference<>(CronJobResult.SUCCESS);

        while (true) {
            LOG.info("Starting migration of batch: {}", searchPageData.getPagination().getCurrentPage() + 1);
            searchPageData = paginatedFlexibleSearchService.search(pfsParam);
            if (BooleanUtils.isTrue(cronJob.getRequestAbort())) {
                LOG.info("******** Media migration aborted. ********");
                executorService.shutdown();
                break;
            } else {
                futures = searchPageData.getResults().stream().map(product -> executorService.submit(new MediaMigrationWorker(product, mediaMigrationProcessor))).toList();
                futures.forEach(future -> {
                    try {
                        future.get();
                    } catch (Exception exception) {
                        LOG.error("Error occurred while migrating media.", exception);
                        result.set(CronJobResult.FAILURE);
                    }
                });
                LOG.info("Finished migration of batch: {}", searchPageData.getPagination().getCurrentPage() + 1);
            }

            if (searchPageData.getPagination().getHasNext()) {
                searchPageData.getPagination().setCurrentPage(searchPageData.getPagination().getCurrentPage() + 1);
                pfsParam.setSearchPageData(searchPageData);
            } else {
                LOG.info("******** Media migration completed. ********");
                executorService.shutdown();
                break;
            }
        }

        return new PerformResult(result.get(), CronJobStatus.FINISHED);
    }

    @Override
    public boolean isAbortable() {
        return true;
    }

    public void setMediaMigrationProcessor(MediaMigrationProcessor mediaMigrationProcessor) {
        this.mediaMigrationProcessor = mediaMigrationProcessor;
    }

    public void setPaginatedFlexibleSearchService(PaginatedFlexibleSearchService paginatedFlexibleSearchService) {
        this.paginatedFlexibleSearchService = paginatedFlexibleSearchService;
    }

}
