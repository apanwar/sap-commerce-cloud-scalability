import de.hybris.platform.catalog.CatalogVersionService
import de.hybris.platform.catalog.model.CatalogUnawareMediaModel
import de.hybris.platform.catalog.model.CatalogVersionModel
import de.hybris.platform.core.model.media.CatalogUnawareMediaContainerModel
import de.hybris.platform.core.model.media.MediaContainerModel
import de.hybris.platform.core.model.media.MediaModel
import de.hybris.platform.core.model.product.ProductModel
import de.hybris.platform.core.servicelayer.data.PaginationData
import de.hybris.platform.core.servicelayer.data.SearchPageData
import de.hybris.platform.servicelayer.media.MediaService
import de.hybris.platform.servicelayer.model.ModelService
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery
import de.hybris.platform.servicelayer.search.FlexibleSearchService
import de.hybris.platform.servicelayer.search.paginated.PaginatedFlexibleSearchParameter
import de.hybris.platform.servicelayer.search.paginated.PaginatedFlexibleSearchService
import de.hybris.platform.servicelayer.user.UserService
import org.apache.commons.collections.CollectionUtils
import org.slf4j.LoggerFactory

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
            if (it.pk != product.pk) { // To ensure the replacement happens in other catalog versions only
                def propertyValue = it.getProperty(attributeName)
                if (propertyValue) {
                    if (propertyValue instanceof Collection) {
                        if (propertyValue.isEmpty()) {
                            LOG.debug('Empty collection. Ignoring...')
                        } else if (propertyValue.iterator().next() instanceof MediaModel) {
                            if (!(propertyValue.iterator().next() instanceof CatalogUnawareMediaModel)) {
                                itemsToRemove.addAll(propertyValue)
                                def migratedCollection = product.getProperty(attributeName) as Collection<CatalogUnawareMediaModel>
                                def collectionValue = new ArrayList<CatalogUnawareMediaModel>(migratedCollection)
                                modelService.setAttributeValue(it, attributeName, collectionValue)
                                itemsToSave.add(it)
                            }
                        } else { // Media Container
                            Collection<CatalogUnawareMediaContainerModel> migratedContainersFromOtherVersion = product.getProperty(attributeName)
                            def migratedContainers = new ArrayList<CatalogUnawareMediaContainerModel>(migratedContainersFromOtherVersion)
                            itemsToSave.addAll(migratedContainers)
                            itemsToRemove.addAll(propertyValue)
                        }
                    } else {
                        if (!(propertyValue instanceof CatalogUnawareMediaModel)) {
                            def migratedMedia = product.getProperty(attributeName)
                            if (migratedMedia) {
                                itemsToRemove.add(propertyValue)
                                modelService.setAttributeValue(it, attributeName, product.getProperty(attributeName))

                                itemsToSave.add(it)
                            }
                        }
                    }


                }
            }
        }
        if (CollectionUtils.isNotEmpty(itemsToSave)) {
            modelService.saveAll(itemsToSave)
        }
        if (CollectionUtils.isNotEmpty(itemsToRemove)) {
            modelService.removeAll(itemsToRemove)
        }

    }

    def migrateMediaAttribute(MediaModel media, MediaModel originalMedia) {
        CatalogUnawareMediaModel catalogUnAwareMedia = modelService.create(CatalogUnawareMediaModel)
        catalogUnAwareMedia.code = originalMedia.code
        media.code = 'archived_' + originalMedia.code
        catalogUnAwareMedia.mediaFormat = media.mediaFormat
        catalogUnAwareMedia.folder = media.folder
        modelService.saveAll(originalMedia, catalogUnAwareMedia)
        mediaService.copyData(originalMedia, catalogUnAwareMedia)
        return catalogUnAwareMedia
    }

    def migrateMediaAttributes(ProductModel product, List<String> attributes, List<String> collectionAttributes, List<String> containerAttributes) {
        modelService.refresh(product)
        if (attributes) {
            attributes.each {
                def originalMedia = product.getProperty(it) as MediaModel
                if (originalMedia && !(originalMedia instanceof CatalogUnawareMediaModel)) {
                    LOG.debug('**** Migrating media assigned to [{}] attribute is [{}]', it, originalMedia.code)
                    LOG.debug('Media type before migration [{}]', originalMedia.itemtype)
                    def migratedMedia = migrateMediaAttribute(originalMedia, originalMedia)

                    modelService.setAttributeValue(product, it, migratedMedia)
                    modelService.save(product)
                    this.replaceMediaInMediaCollections(product, originalMedia, migratedMedia, collectionAttributes)
                    this.replaceMediaInMediaContainers(product, originalMedia, migratedMedia, containerAttributes)
                    modelService.remove(originalMedia)
                    LOG.debug('Media type after migration [{}]', migratedMedia.itemtype)
                    LOG.debug('Migrated media assigned to [{}] attribute is [{}] ****', it, migratedMedia.code)
                }
            }
        }
    }

    def replaceMediaInMediaCollections(ProductModel product, MediaModel original, MediaModel migrated, List<String> collections) {
        if (collections) {
            collections.each {
                LOG.debug('Replace media [{}] references in media collection attribute [{}]', original.code, it)
                def mediaCollection = product.getProperty(it) as Collection<MediaModel>
                def newCollection = getUpdatedMediaCollection(mediaCollection, original, migrated)
                if (null != newCollection) {
                    modelService.setAttributeValue(product, it, newCollection)
                    modelService.save(product)
                }
                LOG.debug('Replaced media [{}] references in media collection attribute [{}]', original.code, it)
            }
        }
    }

    def replaceMediaInMediaContainers(ProductModel product, MediaModel original, MediaModel migrated, List<String> containers) {
        if (containers) {
            containers.each {
                modelService.refresh(product)
                def mediaContainers = product.getProperty(it) as List<MediaContainerModel>
                if (CollectionUtils.isNotEmpty(mediaContainers)) {
                    mediaContainers.each { mediaContainer ->
                        modelService.refresh(mediaContainer)
                        LOG.debug('Replace media [{}] references in media container [{}]', original.code, mediaContainer.qualifier)
                        def mediaCollection = mediaContainer.medias
                        def newCollection = getUpdatedMediaCollection(mediaCollection, original, migrated)
                        if (null != newCollection) {
                            mediaContainer.medias = newCollection
                            modelService.save(mediaContainer)
                        }
                        LOG.debug('Replaced media [{}] references in media container [{}]', original.code, mediaContainer.qualifier)
                    }
                }
            }
        }
    }

    static def getUpdatedMediaCollection(Collection<MediaModel> originalCollection, MediaModel original, MediaModel migrated) {

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
                def newMediaCollection = mediaCollection
                LOG.debug('###### Migrating media collection assigned to [{}]', it)
                remainingCollections.remove(it)
                if (CollectionUtils.isEmpty(mediaCollection)) {
                    LOG.debug('No media available in collection assigned to [{}]', it)
                } else {
                    mediaCollection.each { originalMedia ->
                        if (originalMedia && !(originalMedia instanceof CatalogUnawareMediaModel)) {
                            def migratedMedia = migrateMediaAttribute(originalMedia, originalMedia)
                            def updatedCollection = getUpdatedMediaCollection(newMediaCollection, originalMedia, migratedMedia)
                            if (null != updatedCollection) {
                                newMediaCollection = updatedCollection
                            }
                            if (!remainingCollections.isEmpty()) {
                                LOG.debug('Replace media [{}] in other collections', originalMedia.code)
                                this.replaceMediaInMediaCollections(product, originalMedia, migratedMedia, remainingCollections)
                                LOG.debug('Replaced media [{}] in other collections', originalMedia.code)
                            }

                            LOG.debug('Replace media [{}] references in media containers', originalMedia.code)
                            this.replaceMediaInMediaContainers(product, originalMedia, migratedMedia, containers)
                            LOG.debug('Replaced media [{}] references in media containers', originalMedia.code)
                            modelService.remove(originalMedia)
                        } else {
                            LOG.debug('Media already migrated or does not exist')
                        }

                        modelService.setAttributeValue(product, it, newMediaCollection)
                        modelService.save(product)
                    }
                    LOG.debug('Migrated media collection assigned to [{}] ######', it)
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
                    LOG.debug('Media container [{}] is empty', it)
                } else {
                    def migratedMediaContainers = new ArrayList<CatalogUnawareMediaContainerModel>()
                    mediaContainers.each { mediaContainer ->
                        modelService.refresh(mediaContainer)
                        CatalogUnawareMediaContainerModel catalogUnawareMediaContainer = modelService.create(CatalogUnawareMediaContainerModel)
                        LOG.debug('!!!!!!!!! Migrating media for media container [{}]', mediaContainer.qualifier)
                        def mediaCollection = mediaContainer.medias
                        def newMediaCollection = new ArrayList()
                        def toRemove = []
                        mediaCollection.each { originalMedia ->
                            if (originalMedia && !(originalMedia instanceof CatalogUnawareMediaModel)) {
                                LOG.debug('Migrating media assigned to [{}] attribute is [{}]', it, originalMedia.code)
                                LOG.debug('Media type before migration [{}]', originalMedia.itemtype)
                                def migratedMedia = migrateMediaAttribute(originalMedia, originalMedia)
                                newMediaCollection.add(migratedMedia)
                                toRemove.add(originalMedia)
                                LOG.debug('Media type after migration [{}]', migratedMedia.itemtype)
                                LOG.debug('Migrated media assigned to [{}] attribute is [{}]', it, migratedMedia.code)
                            }
                        }
                        if (!newMediaCollection.isEmpty()) {
                            catalogUnawareMediaContainer.qualifier = mediaContainer.qualifier
                            catalogUnawareMediaContainer.medias = newMediaCollection
                            toRemove.add(mediaContainer)
                            modelService.save(catalogUnawareMediaContainer)
                            migratedMediaContainers.add(catalogUnawareMediaContainer)
                            modelService.removeAll(toRemove)
                        }

                        LOG.debug('Migrated media for media container [{}] !!!!!!!!', catalogUnawareMediaContainer.qualifier)
                    }
                    modelService.setAttributeValue(product, it, migratedMediaContainers)
                    modelService.save(product)
                }
            }
        }
    }

    def migrateProductMediaForCatalogVersion(CatalogVersionModel catalogVersion, int batchSize) {
        def query = 'SELECT {PK} FROM {Product} WHERE {catalogVersion}=?catalogVersion'

        def paginationData = new PaginationData()
        paginationData.pageSize = batchSize
        paginationData.currentPage = 0

        def searchPageData = new SearchPageData<ProductModel>()
        searchPageData.pagination = paginationData

        def fsq = new FlexibleSearchQuery(query)
        fsq.addQueryParameter('catalogVersion', catalogVersion)
        fsq.disableSearchRestrictions = true

        def pfsParam = new PaginatedFlexibleSearchParameter()
        pfsParam.flexibleSearchQuery = fsq
        pfsParam.searchPageData = searchPageData
        def productMediaAttributes = ['picture', 'thumbnail']
        def productMediaCollectionAttributes = ['detail', 'logo', 'normal', 'others', 'thumbnails']
        def productMediaContainerAttributes = ['galleryImages']
        userService.setCurrentUser(userService.getAdminUser())

        while (true) {
            LOG.info('*******************************************')
            LOG.info('Starting migration of batch: {}', searchPageData.pagination.currentPage + 1)
            LOG.info('*******************************************')
            searchPageData = paginatedFlexibleSearchService.<ProductModel> search(pfsParam)
            searchPageData.results.each {
                LOG.info('Processing media migration for product [{}]', it.code)
                migrateMediaAttributes(it, productMediaAttributes, productMediaCollectionAttributes, productMediaContainerAttributes)

                productMediaAttributes.each { attribute ->
                    replaceMediaInCatalogVersions(it, attribute)
                }
                migrateMediaCollectionAttributes(it, productMediaCollectionAttributes, productMediaContainerAttributes)
                productMediaCollectionAttributes.each { attribute ->
                    replaceMediaInCatalogVersions(it, attribute)
                }
                migrateMediaContainerAttributes(it, productMediaContainerAttributes)
                productMediaContainerAttributes.each { attribute ->
                    replaceMediaInCatalogVersions(it, attribute)
                }
                LOG.info('Processed media migration for product [{}]', it.code)
            }

            LOG.info('*******************************************')
            LOG.info('Completed migration of batch: {}', searchPageData.pagination.currentPage + 1)
            LOG.info('*******************************************')

            if (searchPageData.pagination.hasNext) {
                searchPageData.pagination.currentPage = searchPageData.pagination.currentPage + 1
            } else {
                break
            }
        }
    }

}

def catalogVersion = (catalogVersionService as CatalogVersionService).getCatalogVersion('apparelProductCatalog', 'Online')

def migrationUtility = MediaToCatalogUnawareMediaMigrationUtil.getInstance()

migrationUtility.modelService = spring.getBean('modelService')
migrationUtility.flexibleSearchService = spring.getBean('flexibleSearchService')
migrationUtility.mediaService = spring.getBean('mediaService')
migrationUtility.paginatedFlexibleSearchService = spring.getBean('paginatedFlexibleSearchService')
migrationUtility.userService = spring.getBean('userService')

migrationUtility.migrateProductMediaForCatalogVersion(catalogVersion, 100)


