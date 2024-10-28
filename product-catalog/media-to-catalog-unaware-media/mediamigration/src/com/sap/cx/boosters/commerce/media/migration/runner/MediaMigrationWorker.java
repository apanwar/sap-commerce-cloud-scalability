package com.sap.cx.boosters.commerce.media.migration.runner;

import com.sap.cx.boosters.commerce.media.migration.processor.MediaMigrationProcessor;
import de.hybris.platform.core.Registry;
import de.hybris.platform.core.model.product.ProductModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

public class MediaMigrationWorker implements Callable<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(MediaMigrationWorker.class);

    private final ProductModel product;

    private final MediaMigrationProcessor processor;

    public MediaMigrationWorker(ProductModel product, MediaMigrationProcessor processor) {
        this.product = product;
        this.processor = processor;
    }

    @Override
    public Void call() throws Exception{
        Registry.activateMasterTenant();
        LOG.info("[Product-{}] - Starting migration", this.product.getCode());
        try {
            processor.process(product);
        } finally {
            LOG.info("[Product-{}] - Ending migration.", this.product.getCode());
        }
        return null;
    }

}
