package mediamigration


import de.hybris.platform.catalog.CatalogVersionService
import de.hybris.platform.catalog.model.CatalogUnawareMediaModel
import de.hybris.platform.catalog.model.CatalogVersionModel
import de.hybris.platform.core.Registry
import de.hybris.platform.core.model.media.CatalogUnawareMediaContainerModel
import de.hybris.platform.core.model.media.MediaContainerModel
import de.hybris.platform.core.model.media.MediaModel
import de.hybris.platform.core.model.product.ProductModel
import de.hybris.platform.core.servicelayer.data.PaginationData
import de.hybris.platform.core.servicelayer.data.SearchPageData
import de.hybris.platform.servicelayer.exceptions.ModelNotFoundException
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException
import de.hybris.platform.servicelayer.media.MediaService
import de.hybris.platform.servicelayer.model.ModelService
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery
import de.hybris.platform.servicelayer.search.FlexibleSearchService
import de.hybris.platform.servicelayer.search.paginated.PaginatedFlexibleSearchParameter
import de.hybris.platform.servicelayer.search.paginated.PaginatedFlexibleSearchService
import de.hybris.platform.servicelayer.user.UserService
import org.apache.commons.collections.CollectionUtils
import org.slf4j.LoggerFactory

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MediaToCatalogUnawareMediaMigrationUtil {

    private static final def LOG = LoggerFactory.getLogger('media-to-catalog-unaware-media-migration')

    private static final MediaToCatalogUnawareMediaMigrationUtil instance = new MediaToCatalogUnawareMediaMigrationUtil()

    ModelService modelService

    FlexibleSearchService flexibleSearchService

    MediaService mediaService

    PaginatedFlexibleSearchService paginatedFlexibleSearchService

    UserService userService

    private MediaToCatalogUnawareMediaMigrationUtil() {
        // Private constructor
    }

    static MediaToCatalogUnawareMediaMigrationUtil getInstance() {
        return instance
    }

    static def getItemsToRemoveFromCollection(Collection<MediaModel> collection) {
        def itemsToRemove = new ArrayList<MediaModel>()
        collection.each {
            if (it instanceof CatalogUnawareMediaModel) {
                LOG.warn('Media {} is catalog unaware. No need to remove it', it)
            } else {
                LOG.debug('Media {} is catalog aware. Need to remove it', it)
                itemsToRemove.add(it)
            }
        }
        return itemsToRemove
    }

    static def getItemsToRemoveFromContainers(Collection<MediaContainerModel> containers) {
        def itemsToRemove = new ArrayList()
        containers.each {
            def mediasToRemove = getItemsToRemoveFromCollection(it.medias)
            if (!mediasToRemove.isEmpty()) {
                itemsToRemove.addAll(mediasToRemove)
            }
            if (it instanceof CatalogUnawareMediaContainerModel) {
                LOG.warn('Container {} is catalog unaware. No need to remove it', it)
            } else {
                LOG.debug('Container {} is catalog aware. Need to remove it', it)
                itemsToRemove.add(it)
            }
        }
        return itemsToRemove
    }

    def replaceMediaInCatalogVersions(ProductModel product, String attributeName) {
        modelService.refresh(product)
        def query = 'SELECT {PK} FROM {Product} WHERE {code}=?code'
        def fsq = new FlexibleSearchQuery(query)
        fsq.addQueryParameter('code', product.code)
        fsq.disableSearchRestrictions = true
        def products = flexibleSearchService.<ProductModel> search(fsq).getResult()

        def itemsToRemove = []
        def itemsToSave = []

        products.each {
            modelService.refresh(it)
            if (it.pk != product.pk) { // To ensure the replacement happens in other catalog versions only
                def propertyValue = product.getProperty(attributeName)
                if (propertyValue) {
                    if (propertyValue instanceof Collection) {
                        if (propertyValue.isEmpty()) {
                            LOG.info('Empty collection. Ignoring...')
                        } else if (propertyValue.iterator().next() instanceof CatalogUnawareMediaModel) {
                            itemsToRemove.addAll(getItemsToRemoveFromCollection(it.getProperty(attributeName)))
                            def migratedCollection = propertyValue as Collection<CatalogUnawareMediaModel>
                            def collectionValue = new ArrayList<CatalogUnawareMediaModel>(migratedCollection)
                            modelService.setAttributeValue(it, attributeName, collectionValue)
                            itemsToSave.add(it)
                        } else if (propertyValue.iterator().next() instanceof CatalogUnawareMediaContainerModel) {
                            // Media Container
                            LOG.info('Updating containers from other version')
                            itemsToRemove.addAll(getItemsToRemoveFromContainers(it.getProperty(attributeName)))

                            Collection<CatalogUnawareMediaContainerModel> migratedContainersFromOtherVersion = product.getProperty(attributeName)

                            def migratedContainers = new ArrayList<CatalogUnawareMediaContainerModel>()
                            migratedContainers.addAll(migratedContainersFromOtherVersion)
                            LOG.info('Found ' + migratedContainers.size() + ' Migrated containers from other versions')
                            modelService.setAttributeValue(it, attributeName, migratedContainers)
                            itemsToSave.add(it)
                        } else {
                            LOG.info('Unknown collection. Ignoring...')
                        }
                    } else if (propertyValue instanceof CatalogUnawareMediaModel) {
                        if (!(it.getProperty(attributeName) instanceof CatalogUnawareMediaModel)) {
                            itemsToRemove.add(it.getProperty(attributeName))
                        }
                        modelService.setAttributeValue(it, attributeName, propertyValue)
                        itemsToSave.add(it)
                    }
                }
            }
        }

        if (CollectionUtils.isNotEmpty(itemsToSave)) {
            LOG.info('Items to save {}', itemsToSave)
            modelService.saveAll(itemsToSave)
        } else {
            LOG.warn('Nothing to save !!!')
        }
        if (CollectionUtils.isNotEmpty(itemsToRemove)) {
            LOG.info('Items to remove {}', itemsToRemove)
            modelService.removeAll(itemsToRemove)
        } else {
            LOG.warn('Nothing to remove !!!')
        }

    }

    def migrateMediaAttribute(MediaModel originalMedia) {
        migrateMediaAttribute(originalMedia, null)
    }

    def migrateMediaAttribute(MediaModel originalMedia, MediaModel master) {
        if (originalMedia instanceof CatalogUnawareMediaModel) {
            return originalMedia
        } else {
            def query = new FlexibleSearchQuery('SELECT {PK} FROM {CatalogUnawareMedia} WHERE {code}=?code')
            query.addQueryParameter('code', originalMedia.code)
            CatalogUnawareMediaModel catalogUnAwareMedia
            try {
                catalogUnAwareMedia = flexibleSearchService.searchUnique(query)
            } catch (UnknownIdentifierException | ModelNotFoundException ignored) {
                catalogUnAwareMedia = modelService.create(CatalogUnawareMediaModel)
            }

            catalogUnAwareMedia.code = originalMedia.code
            originalMedia.code = 'archived_' + originalMedia.code
            catalogUnAwareMedia.mediaFormat = originalMedia.mediaFormat
            catalogUnAwareMedia.folder = originalMedia.folder
            if (null == master) {
                catalogUnAwareMedia.originalDataPK = originalMedia.originalDataPK
                catalogUnAwareMedia.original = originalMedia.original
            } else {
                catalogUnAwareMedia.originalDataPK = master.dataPK
                catalogUnAwareMedia.original = master
            }

            modelService.saveAll(originalMedia, catalogUnAwareMedia)
            mediaService.copyData(originalMedia, catalogUnAwareMedia)
            return catalogUnAwareMedia
        }
    }

    def migrateMediaAttributes(ProductModel product, List<String> attributes, List<String> collectionAttributes, List<String> containerAttributes) {
        modelService.refresh(product)
        if (attributes) {
            attributes.each {
                def originalMedia = product.getProperty(it) as MediaModel
                if (originalMedia && !(originalMedia instanceof CatalogUnawareMediaModel)) {
                    LOG.info('**** Migrating media assigned to [{}] attribute is [{}]', it, originalMedia.code)
                    LOG.info('Media type before migration [{}]', originalMedia.itemtype)
                    def migratedMedia = migrateMediaAttribute(originalMedia)
                    modelService.setAttributeValue(product, it, migratedMedia)
                    modelService.save(product)
                    this.replaceMediaInMediaCollections(product, originalMedia, migratedMedia, collectionAttributes)
                    this.replaceMediaInMediaContainers(product, originalMedia, migratedMedia, containerAttributes)
                    modelService.remove(originalMedia)
                    LOG.info('Media type after migration [{}]', migratedMedia.itemtype)
                    LOG.info('Migrated media assigned to [{}] attribute is [{}] ****', it, migratedMedia.code)
                }
            }
        }
    }

    def replaceMediaInMediaCollections(ProductModel product, MediaModel original, CatalogUnawareMediaModel migrated, List<String> collections) {
        if (collections) {
            collections.each {
                modelService.refresh(product)
                LOG.info('Replace media [{}] references in media collection attribute [{}]', original.code, it)
                def mediaCollection = product.getProperty(it) as Collection<MediaModel>
                def newCollection = getUpdatedMediaCollection(mediaCollection, original, migrated)
                if (null != newCollection) {
                    modelService.setAttributeValue(product, it, newCollection)
                    modelService.save(product)
                }
                LOG.info('Replaced media [{}] references in media collection attribute [{}]', original.code, it)
            }
        }
    }

    def replaceMediaInMediaContainers(ProductModel product, MediaModel original, CatalogUnawareMediaModel migrated, List<String> containers) {
        if (containers) {
            containers.each {
                modelService.refresh(product)
                def mediaContainers = product.getProperty(it) as List<MediaContainerModel>
                if (CollectionUtils.isNotEmpty(mediaContainers)) {
                    mediaContainers.each { mediaContainer ->
                        modelService.refresh(mediaContainer)
                        LOG.info('Replace media [{}] references in media container [{}]', original.code, mediaContainer.qualifier)
                        def mediaCollection = mediaContainer.medias
                        def newCollection = getUpdatedMediaCollection(mediaCollection, original, migrated)
                        if (null != newCollection) {
                            mediaContainer.medias = newCollection
                            modelService.save(mediaContainer)
                        }
                        LOG.info('Replaced media [{}] references in media container [{}]', original.code, mediaContainer.qualifier)
                    }
                }
            }
        }
    }

    static def getUpdatedMediaCollection(Collection<MediaModel> originalCollection, MediaModel original, CatalogUnawareMediaModel migrated) {

        if (CollectionUtils.isNotEmpty(originalCollection)) {
            def newCollection = new ArrayList<MediaModel>()
            originalCollection.each { media ->
                if (media.pk == original.pk) {
                    newCollection.add(migrated)
                } else {
                    newCollection.add(media)
                }
            }
            return newCollection
        }
        return null
    }

    def migrateMediaCollectionAttributes(ProductModel product, List<String> attributes, List<String> containers) {
        if (attributes) {
            List<String> remainingCollections = new ArrayList<String>(attributes)
            attributes.each {
                modelService.refresh(product)
                def mediaCollection = product.getProperty(it) as Collection<MediaModel>
                def newMediaCollection = new ArrayList<MediaModel>()
                LOG.info('###### Migrating media collection assigned to [{}]', it)
                remainingCollections.remove(it)
                if (CollectionUtils.isEmpty(mediaCollection)) {
                    LOG.info('No media available in collection assigned to [{}]', it)
                } else {
                    mediaCollection.each { originalMedia ->
                        if (originalMedia instanceof CatalogUnawareMediaModel) {
                            newMediaCollection.add(originalMedia)
                        } else {
                            def migratedMedia = migrateMediaAttribute(originalMedia)
                            newMediaCollection.add(migratedMedia)
                            if (!remainingCollections.isEmpty()) {
                                LOG.info('Replace media [{}] in other collections', originalMedia.code)
                                this.replaceMediaInMediaCollections(product, originalMedia, migratedMedia, remainingCollections)
                                LOG.info('Replaced media [{}] in other collections', originalMedia.code)
                            }

                            LOG.info('Replace media [{}] references in media containers', originalMedia.code)
                            this.replaceMediaInMediaContainers(product, originalMedia, migratedMedia, containers)
                            LOG.info('Replaced media [{}] references in media containers', originalMedia.code)

                        }

                        modelService.setAttributeValue(product, it, newMediaCollection)
                        modelService.save(product)
                    }
                    if (CollectionUtils.isNotEmpty(mediaCollection)) {
                        mediaCollection.each {
                            if (!(it instanceof CatalogUnawareMediaModel)) {
                                modelService.remove(it)
                            }
                        }
                    }
                    LOG.info('Migrated media collection assigned to [{}] ######', it)
                }
            }
        }
    }

    def migrateMediaContainerAttributes(ProductModel product, List<String> attributes) {
        modelService.refresh(product)
        if (attributes) {
            attributes.each {
                def mediaContainers = product.getProperty(it) as Collection<MediaContainerModel>
                if (CollectionUtils.isEmpty(mediaContainers)) {
                    LOG.info('Media container [{}] is empty', it)
                } else {
                    def migratedMediaContainers = new ArrayList<CatalogUnawareMediaContainerModel>()
                    mediaContainers.each { mediaContainer ->
                        modelService.refresh(mediaContainer)

                        if (mediaContainer instanceof CatalogUnawareMediaContainerModel) {
                            LOG.info('Container is already migrated')
                            migratedMediaContainers.add(mediaContainer)
                        } else {
                            def query = new FlexibleSearchQuery('SELECT {PK} FROM {CatalogUnawareMediaContainer} WHERE {qualifier}=?qualifier')
                            query.addQueryParameter('qualifier', mediaContainer.qualifier)
                            CatalogUnawareMediaContainerModel catalogUnawareMediaContainer
                            try {
                                catalogUnawareMediaContainer = flexibleSearchService.searchUnique(query)
                            } catch (UnknownIdentifierException | ModelNotFoundException ignored) {
                                catalogUnawareMediaContainer = modelService.create(CatalogUnawareMediaContainerModel)
                            }
                            def master = mediaContainer.master
                            // convert master media first if exists
                            def migratedMaster = master == null ? null : migrateMediaAttribute(master)

                            LOG.info('!!!!!!!!! Migrating media for media container [{}]', mediaContainer.qualifier)
                            def mediaCollection = mediaContainer.medias
                            def newMediaCollection = new ArrayList()
                            def toRemove = []
                            mediaCollection.each { originalMedia ->
                                if (originalMedia instanceof CatalogUnawareMediaModel) {

                                    newMediaCollection.add(originalMedia)
                                } else {
                                    LOG.info('Migrating media assigned to [{}] attribute is [{}]', it, originalMedia.code)
                                    LOG.info('Media type before migration [{}]', originalMedia.itemtype)
                                    def migratedMedia
                                    if (null != migratedMaster && originalMedia.pk == master.pk) {
                                        migratedMedia = migratedMaster
                                    } else {
                                        migratedMedia = migrateMediaAttribute(originalMedia, migratedMaster)
                                    }
                                    newMediaCollection.add(migratedMedia)
                                    toRemove.add(originalMedia)
                                    LOG.info('Media type after migration [{}]', migratedMedia.itemtype)
                                    LOG.info('Migrated media assigned to [{}] attribute is [{}]', it, migratedMedia.code)
                                }
                            }
                            if (newMediaCollection.isEmpty()) {
                                LOG.info('No new collection')
                            } else {
                                catalogUnawareMediaContainer.qualifier = mediaContainer.qualifier
                                catalogUnawareMediaContainer.conversionGroup = mediaContainer.conversionGroup
                                catalogUnawareMediaContainer.medias = newMediaCollection
                                toRemove.add(mediaContainer)
                                modelService.save(catalogUnawareMediaContainer)
                                migratedMediaContainers.add(catalogUnawareMediaContainer)
                                modelService.removeAll(toRemove)
                            }
                            LOG.info('Migrated media for media container [{}] !!!!!!!!', catalogUnawareMediaContainer.qualifier)
                        }
                    }
                    if (CollectionUtils.isNotEmpty(migratedMediaContainers)) {
                        modelService.setAttributeValue(product, it, migratedMediaContainers)
                        modelService.save(product)
                    }
                }
            }
        }
    }

    def migrateProductMediaForCatalogVersion(CatalogVersionModel catalogVersion, int batchSize, int migrationWorkers) {
        try {
            def query = 'SELECT {' + ProductModel.PK + '} FROM {' + ProductModel._TYPECODE + '} WHERE {' + ProductModel.CATALOGVERSION + '} =?' + ProductModel.CATALOGVERSION + ' ORDER BY { ' + ProductModel.CREATIONTIME + '}'

            def paginationData = new PaginationData()
            paginationData.pageSize = batchSize
            paginationData.currentPage = 0

            def searchPageData = new SearchPageData<ProductModel>()
            searchPageData.pagination = paginationData

            def fsq = new FlexibleSearchQuery(query)
            fsq.addQueryParameter(ProductModel.CATALOGVERSION, catalogVersion)
            fsq.disableSearchRestrictions = true

            def pfsParam = new PaginatedFlexibleSearchParameter()
            pfsParam.flexibleSearchQuery = fsq
            pfsParam.searchPageData = searchPageData

            userService.setCurrentUser(userService.getAdminUser())
            List<Future<Void>> futures = new ArrayList<>()
            def executorService = Executors.newFixedThreadPool(migrationWorkers)
            while (true) {
                LOG.info('*******************************************')
                LOG.info('Starting migration of batch: {}', searchPageData.pagination.currentPage + 1)
                LOG.info('*******************************************')
                searchPageData = paginatedFlexibleSearchService.<ProductModel> search(pfsParam)
                searchPageData.results.each {
                    futures.add(executorService.submit(new MediaMigrationWorker(it)))
                }

                futures.each {
                    try {
                        it.get()
                    } catch (Exception exception) {
                        LOG.error('Error occurred while migrating media.', exception)
                    }
                }

                LOG.info('*******************************************')
                LOG.info('Completed migration of batch: {}', searchPageData.pagination.currentPage + 1)
                LOG.info('*******************************************')

                if (searchPageData.pagination.hasNext) {
                    searchPageData.pagination.currentPage = searchPageData.pagination.currentPage + 1
                    pfsParam.searchPageData = searchPageData
                } else {
                    break
                }
            }
        } catch (Exception exception) {
            LOG.error('migration failed', exception)
        }
    }

    class MediaMigrationWorker implements Callable<Void> {

        def MEDIA_ATTRIBUTES = [ProductModel.PICTURE, ProductModel.THUMBNAIL]
        def MEDIA_COLLECTION_ATTRIBUTES = [ProductModel.DETAIL, ProductModel.LOGO, ProductModel.NORMAL, ProductModel.OTHERS, ProductModel.THUMBNAILS]
        def MEDIA_CONTAINER_ATTRIBUTES = [ProductModel.GALLERYIMAGES]

        def LOG = LoggerFactory.getLogger(com.sap.cx.boosters.commerce.media.migration.runner.MediaMigrationWorker)

        private final ProductModel product

        MediaMigrationWorker(ProductModel product) {
            this.product = product
        }

        @Override
        Void call() throws Exception {
            Registry.activateMasterTenant()
            LOG.info('[Product-{}] - Starting migration', this.product.code)
            try {
                // Migrate the media attributes for the product
                migrateMediaAttributes(product, MEDIA_ATTRIBUTES, MEDIA_COLLECTION_ATTRIBUTES, MEDIA_CONTAINER_ATTRIBUTES)
                // Replace the migrated media in the products of other catalog versions
                MEDIA_ATTRIBUTES.each { replaceMediaInCatalogVersions(product, it) }

                // Migrate the media collection attributes for the product
                migrateMediaCollectionAttributes(product, MEDIA_COLLECTION_ATTRIBUTES, MEDIA_CONTAINER_ATTRIBUTES)
                // Replace the migrated media collections in the products of other catalog versions
                MEDIA_COLLECTION_ATTRIBUTES.each { replaceMediaInCatalogVersions(product, it) }

                // Migrate the media collection attributes for the product
                migrateMediaContainerAttributes(product, MEDIA_CONTAINER_ATTRIBUTES)
                // Replace the migrated media containers in the products of other catalog versions
                MEDIA_CONTAINER_ATTRIBUTES.each { replaceMediaInCatalogVersions(product, it) }

            } finally {
                LOG.info('[Product-{}] - Ending migration.', this.product.code)
            }
            return null
        }
    }

}


def migrationUtility = MediaToCatalogUnawareMediaMigrationUtil.getInstance()

migrationUtility.modelService = spring.getBean('modelService')
migrationUtility.flexibleSearchService = spring.getBean('flexibleSearchService')
migrationUtility.mediaService = spring.getBean('mediaService')
migrationUtility.paginatedFlexibleSearchService = spring.getBean('paginatedFlexibleSearchService')
migrationUtility.userService = spring.getBean('userService')

def catalogVersion = (catalogVersionService as CatalogVersionService).getCatalogVersion('apparelProductCatalog', 'Online')
migrationUtility.migrateProductMediaForCatalogVersion(catalogVersion, 100, 8)

