package com.sap.cx.boosters.commerce.media.migration.processor.impl;

import com.sap.cx.boosters.commerce.media.migration.processor.MediaMigrationProcessor;
import de.hybris.platform.catalog.model.CatalogUnawareMediaModel;
import de.hybris.platform.core.model.media.CatalogUnawareMediaContainerModel;
import de.hybris.platform.core.model.media.MediaContainerModel;
import de.hybris.platform.core.model.media.MediaModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.servicelayer.exceptions.ModelNotFoundException;
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException;
import de.hybris.platform.servicelayer.media.MediaService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public class DefaultMediaMigrationProcessor implements MediaMigrationProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultMediaMigrationProcessor.class);

    private ModelService modelService;

    private FlexibleSearchService flexibleSearchService;

    private MediaService mediaService;

    private List<String> mediaAttributes;

    private List<String> mediaCollectionAttributes;

    private List<String> mediaContainerAttributes;

    @Override
    public void process(ProductModel product) {
        LOG.info("Processing media migration for product [{}]", product.getCode());
        this.migrateMediaAttributes(product);
        mediaAttributes.forEach(attribute -> replaceMediaInCatalogVersions(product, attribute));

        this.migrateMediaCollectionAttributes(product);

        mediaCollectionAttributes.forEach(attribute -> replaceMediaInCatalogVersions(product, attribute));

        this.migrateMediaContainerAttributes(product);
        mediaContainerAttributes.forEach(attribute -> replaceMediaInCatalogVersions(product, attribute));

        LOG.info("Processed media migration for product [{}]", product.getCode());
    }

    private void replaceMediaInCatalogVersions(ProductModel product, String attributeName) {
        modelService.refresh(product);
        var query = new FlexibleSearchQuery("SELECT {PK} FROM {Product} WHERE {code}=?code");
        query.addQueryParameter("code", product.getCode());
        query.setDisableSearchRestrictions(true);
        var products = flexibleSearchService.<ProductModel>search(query).getResult();

        var itemsToRemove = new ArrayList<>();
        var itemsToSave = new ArrayList<>();

        products.forEach(targetProduct -> {
            modelService.refresh(targetProduct);
            if (!Objects.equals(product, targetProduct)) { // To ensure the replacement happens in other catalog versions only
                var propertyValue = product.getProperty(attributeName);
                if (null != propertyValue) {
                    if (propertyValue instanceof Collection<?> collectionPropertyValue) {
                        if (collectionPropertyValue.isEmpty()) {
                            LOG.info("Empty collection. Ignoring...");
                        } else if (collectionPropertyValue.iterator().next() instanceof CatalogUnawareMediaModel) {
                            itemsToRemove.addAll(getItemsToRemoveFromCollection(targetProduct.getProperty(attributeName)));
                            var migratedCollection = (Collection<?>) propertyValue;
                            var collectionValue = new ArrayList<>(migratedCollection);
                            modelService.setAttributeValue(targetProduct, attributeName, collectionValue);
                            itemsToSave.add(targetProduct);
                        } else if (collectionPropertyValue.iterator().next() instanceof CatalogUnawareMediaContainerModel) {
                            // Media Container
                            LOG.info("Updating containers from other version");
                            itemsToRemove.addAll(getItemsToRemoveFromContainers(targetProduct.getProperty(attributeName)));

                            Collection<CatalogUnawareMediaContainerModel> migratedContainersFromOtherVersion = product.getProperty(attributeName);

                            var migratedContainers = new ArrayList<>(migratedContainersFromOtherVersion);
                            LOG.info("Found {} migrated containers from other versions", migratedContainers.size());
                            modelService.setAttributeValue(targetProduct, attributeName, migratedContainers);
                            itemsToSave.add(targetProduct);
                        } else {
                            LOG.info("Unknown collection. Ignoring...");
                        }
                    } else if (propertyValue instanceof CatalogUnawareMediaModel) {
                        if (!(targetProduct.getProperty(attributeName) instanceof CatalogUnawareMediaModel)) {
                            itemsToRemove.add(targetProduct.getProperty(attributeName));
                        }
                        modelService.setAttributeValue(targetProduct, attributeName, propertyValue);
                        itemsToSave.add(targetProduct);
                    }
                }
            }
        });
        if (CollectionUtils.isNotEmpty(itemsToSave)) {
            LOG.info("Items to save {}", itemsToSave);
            modelService.saveAll(itemsToSave);
        } else {
            LOG.warn("Nothing to save !!!");
        }
        if (CollectionUtils.isNotEmpty(itemsToRemove)) {
            LOG.info("Items to remove {}", itemsToRemove);
            modelService.removeAll(itemsToRemove);
        } else {
            LOG.warn("Nothing to remove !!!");
        }
    }

    private Collection<MediaModel> getItemsToRemoveFromCollection(Collection<MediaModel> collection) {
        var itemsToRemove = new ArrayList<MediaModel>();
        collection.forEach(media -> {
            if (media instanceof CatalogUnawareMediaModel) {
                LOG.warn("Media {} is catalog unaware. No need to remove it", media);
            } else {
                LOG.debug("Media {} is catalog aware. Need to remove it", media);
                itemsToRemove.add(media);
            }
        });
        return itemsToRemove;
    }

    private Collection<?> getItemsToRemoveFromContainers(Collection<MediaContainerModel> containers) {
        var itemsToRemove = new ArrayList<>();
        containers.forEach(container -> {
            var mediasToRemove = getItemsToRemoveFromCollection(container.getMedias());
            if (!mediasToRemove.isEmpty()) {
                itemsToRemove.addAll(mediasToRemove);
            }
            if (container instanceof CatalogUnawareMediaContainerModel) {
                LOG.warn("Container {} is catalog unaware. No need to remove it", container);
            } else {
                LOG.debug("Container {} is catalog aware. Need to remove it", container);
                itemsToRemove.add(container);
            }
        });
        return itemsToRemove;
    }


    private void migrateMediaAttributes(ProductModel product) {
        if (CollectionUtils.isNotEmpty(mediaAttributes)) {
            modelService.refresh(product);
            mediaAttributes.forEach(attribute -> {
                MediaModel originalMedia = product.getProperty(attribute);
                if (null != originalMedia && !(originalMedia instanceof CatalogUnawareMediaModel)) {
                    LOG.info("**** Migrating media assigned to [{}] attribute is [{}]", attribute, originalMedia.getCode());
                    LOG.info("Media type before migration [{}]", originalMedia.getItemtype());
                    CatalogUnawareMediaModel migratedMedia = migrateMediaAttribute(originalMedia);
                    modelService.setAttributeValue(product, attribute, migratedMedia);
                    modelService.save(product);
                    this.replaceMediaInMediaCollections(product, originalMedia, migratedMedia, mediaCollectionAttributes);
                    this.replaceMediaInMediaContainers(product, originalMedia, migratedMedia);
                    modelService.remove(originalMedia);
                    LOG.info("Media type after migration [{}]", migratedMedia.getItemtype());
                    LOG.info("Migrated media assigned to [{}] attribute is [{}] ****", attribute, migratedMedia.getCode());
                }
            });
        }
    }

    private void replaceMediaInMediaContainers(ProductModel product, MediaModel original, CatalogUnawareMediaModel migrated) {

        if (CollectionUtils.isNotEmpty(mediaContainerAttributes)) {
            modelService.refresh(product);
            mediaContainerAttributes.forEach(attribute -> {
                List<MediaContainerModel> mediaContainers = product.getProperty(attribute);
                if (org.apache.commons.collections.CollectionUtils.isNotEmpty(mediaContainers)) {
                    mediaContainers.forEach(mediaContainer -> {
                        modelService.refresh(mediaContainer);
                        LOG.info("Replace media [{}] references in media container [{}]", original.getCode(), mediaContainer.getQualifier());
                        var mediaCollection = mediaContainer.getMedias();
                        var newCollection = getUpdatedMediaCollection(mediaCollection, original, migrated);
                        if (null != newCollection) {
                            mediaContainer.setMedias(newCollection);
                            modelService.save(mediaContainer);
                        }
                        LOG.info("Replaced media [{}] references in media container [{}]", original.getCode(), mediaContainer.getQualifier());
                    });
                }
            });
        }
    }

    List<MediaModel> getUpdatedMediaCollection(Collection<MediaModel> originalCollection, MediaModel original, CatalogUnawareMediaModel migrated) {

        if (org.apache.commons.collections.CollectionUtils.isNotEmpty(originalCollection)) {
            var newCollection = new ArrayList<MediaModel>();
            originalCollection.forEach(media -> {
                if (Objects.equals(media, original)) {
                    newCollection.add(migrated);
                } else {
                    newCollection.add(media);
                }
            });
            return newCollection;
        }
        return null;
    }

    private void replaceMediaInMediaCollections(ProductModel product, MediaModel original, CatalogUnawareMediaModel migrated, List<String> collections) {
        if (CollectionUtils.isNotEmpty(collections)) {
            modelService.refresh(product);
            collections.forEach(attribute -> {
                LOG.info("Replace media [{}] references in media collection attribute [{}]", original.getCode(), attribute);
                Collection<MediaModel> mediaCollection = product.getProperty(attribute);
                Collection<MediaModel> newCollection = getUpdatedMediaCollection(mediaCollection, original, migrated);
                if (null != newCollection) {
                    modelService.setAttributeValue(product, attribute, newCollection);
                    modelService.save(product);
                }
                LOG.info("Replaced media [{}] references in media collection attribute [{}]", original.getCode(), attribute);
            });
        }
    }

    private CatalogUnawareMediaModel migrateMediaAttribute(MediaModel originalMedia) {
        return migrateMediaAttribute(originalMedia, null);
    }

    private CatalogUnawareMediaModel migrateMediaAttribute(MediaModel originalMedia, MediaModel master) {
        if (originalMedia instanceof CatalogUnawareMediaModel) {
            return (CatalogUnawareMediaModel) originalMedia;
        } else {
            var query = new FlexibleSearchQuery("SELECT {PK} FROM {CatalogUnawareMedia} WHERE {code}=?code");
            query.addQueryParameter("code", originalMedia.getCode());
            CatalogUnawareMediaModel catalogUnAwareMedia;
            try {
                catalogUnAwareMedia = flexibleSearchService.searchUnique(query);
            } catch (UnknownIdentifierException | ModelNotFoundException ignored) {
                catalogUnAwareMedia = modelService.create(CatalogUnawareMediaModel.class);
            }

            catalogUnAwareMedia.setCode(originalMedia.getCode());
            originalMedia.setCode("archived_" + originalMedia.getCode());
            catalogUnAwareMedia.setMediaFormat(originalMedia.getMediaFormat());
            catalogUnAwareMedia.setFolder(originalMedia.getFolder());
            if (null == master) {
                catalogUnAwareMedia.setOriginalDataPK(originalMedia.getOriginalDataPK());
                catalogUnAwareMedia.setOriginal(originalMedia.getOriginal());
            } else {
                catalogUnAwareMedia.setOriginalDataPK(master.getDataPK());
                catalogUnAwareMedia.setOriginal(master);
            }

            modelService.saveAll(originalMedia, catalogUnAwareMedia);
            mediaService.copyData(originalMedia, catalogUnAwareMedia);
            return catalogUnAwareMedia;
        }
    }

    private void migrateMediaCollectionAttributes(ProductModel product) {
        if (CollectionUtils.isNotEmpty(mediaCollectionAttributes)) {
            List<String> remainingCollections = new ArrayList<>(mediaCollectionAttributes);
            modelService.refresh(product);
            mediaCollectionAttributes.forEach(attribute -> {
                Collection<MediaModel> mediaCollection = product.getProperty(attribute);
                var newMediaCollection = new ArrayList<MediaModel>();
                LOG.info("###### Migrating media collection assigned to [{}]", attribute);
                remainingCollections.remove(attribute);
                if (CollectionUtils.isEmpty(mediaCollection)) {
                    LOG.info("No media available in collection assigned to [{}]", attribute);
                } else {
                    mediaCollection.forEach(originalMedia -> {
                        if (originalMedia instanceof CatalogUnawareMediaModel) {
                            newMediaCollection.add(originalMedia);
                        } else {
                            var migratedMedia = migrateMediaAttribute(originalMedia);
                            newMediaCollection.add(migratedMedia);
                            if (!remainingCollections.isEmpty()) {
                                LOG.info("Replace media [{}] in other collections", originalMedia.getCode());
                                this.replaceMediaInMediaCollections(product, originalMedia, migratedMedia, remainingCollections);
                                LOG.info("Replaced media [{}] in other collections", originalMedia.getCode());
                            }

                            LOG.info("Replace media [{}] references in media containers", originalMedia.getCode());
                            this.replaceMediaInMediaContainers(product, originalMedia, migratedMedia);
                            LOG.info("Replaced media [{}] references in media containers", originalMedia.getCode());

                        }

                        modelService.setAttributeValue(product, attribute, newMediaCollection);
                        modelService.save(product);
                    });
                    if (CollectionUtils.isNotEmpty(mediaCollection)) {
                        var toRemove = mediaCollection.stream().filter(media -> !(media instanceof CatalogUnawareMediaModel)).toList();
                        if (CollectionUtils.isNotEmpty(toRemove)) {
                            modelService.removeAll(toRemove);
                        }
                    }
                    LOG.info("Migrated media collection assigned to [{}] ######", attribute);
                }
            });
        }
    }

    private void migrateMediaContainerAttributes(ProductModel product) {
        if (CollectionUtils.isNotEmpty(mediaContainerAttributes)) {
            modelService.refresh(product);
            mediaContainerAttributes.forEach(attribute -> {
                Collection<MediaContainerModel> mediaContainers = product.getProperty(attribute);
                if (CollectionUtils.isEmpty(mediaContainers)) {
                    LOG.info("Media container [{}] is empty", attribute);
                } else {
                    var migratedMediaContainers = new ArrayList<CatalogUnawareMediaContainerModel>();
                    mediaContainers.forEach(mediaContainer -> {
                        modelService.refresh(mediaContainer);
                        if (mediaContainer instanceof CatalogUnawareMediaContainerModel) {
                            LOG.info("Container is already migrated");
                            migratedMediaContainers.add((CatalogUnawareMediaContainerModel) mediaContainer);
                        } else {
                            var query = new FlexibleSearchQuery("SELECT {PK} FROM {CatalogUnawareMediaContainer} WHERE {qualifier}=?qualifier");
                            query.addQueryParameter("qualifier", mediaContainer.getQualifier());
                            CatalogUnawareMediaContainerModel catalogUnawareMediaContainer;
                            try {
                                catalogUnawareMediaContainer = flexibleSearchService.searchUnique(query);
                            } catch (UnknownIdentifierException | ModelNotFoundException ignored) {
                                catalogUnawareMediaContainer = modelService.create(CatalogUnawareMediaContainerModel.class);
                            }
                            var master = mediaContainer.getMaster();
                            // convert master media first if exists
                            var migratedMaster = master == null ? null : migrateMediaAttribute(master);

                            LOG.info("!!!!!!!!! Migrating media for media container [{}]", mediaContainer.getQualifier());
                            var mediaCollection = mediaContainer.getMedias();
                            var newMediaCollection = new ArrayList<MediaModel>();
                            var toRemove = new ArrayList<>();
                            mediaCollection.forEach(originalMedia -> {
                                if (originalMedia instanceof CatalogUnawareMediaModel) {
                                    newMediaCollection.add(originalMedia);
                                } else {
                                    LOG.info("Migrating media assigned to [{}] attribute is [{}]", attribute, originalMedia.getCode());
                                    LOG.info("Media type before migration [{}]", originalMedia.getItemtype());
                                    CatalogUnawareMediaModel migratedMedia;
                                    if (null != migratedMaster && Objects.equals(originalMedia, master)) {
                                        migratedMedia = migratedMaster;
                                    } else {
                                        migratedMedia = migrateMediaAttribute(originalMedia, migratedMaster);
                                    }
                                    newMediaCollection.add(migratedMedia);
                                    toRemove.add(originalMedia);
                                    LOG.info("Media type after migration [{}]", migratedMedia.getItemtype());
                                    LOG.info("Migrated media assigned to [{}] attribute is [{}]", attribute, migratedMedia.getCode());
                                }
                            });
                            if (newMediaCollection.isEmpty()) {
                                LOG.info("No new collection");
                            } else {
                                catalogUnawareMediaContainer.setQualifier(mediaContainer.getQualifier());
                                catalogUnawareMediaContainer.setConversionGroup(mediaContainer.getConversionGroup());
                                catalogUnawareMediaContainer.setMedias(newMediaCollection);
                                toRemove.add(mediaContainer);
                                modelService.save(catalogUnawareMediaContainer);
                                migratedMediaContainers.add(catalogUnawareMediaContainer);
                                modelService.removeAll(toRemove);
                            }
                            LOG.info("Migrated media for media container [{}] !!!!!!!!", catalogUnawareMediaContainer.getQualifier());
                        }
                    });
                    if (CollectionUtils.isNotEmpty(migratedMediaContainers)) {
                        modelService.setAttributeValue(product, attribute, migratedMediaContainers);
                        modelService.save(product);
                    }
                }
            });
        }
    }

    public void setModelService(ModelService modelService) {
        this.modelService = modelService;
    }

    public void setFlexibleSearchService(FlexibleSearchService flexibleSearchService) {
        this.flexibleSearchService = flexibleSearchService;
    }

    public void setMediaService(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    public void setMediaAttributes(List<String> mediaAttributes) {
        this.mediaAttributes = mediaAttributes;
    }

    public void setMediaCollectionAttributes(List<String> mediaCollectionAttributes) {
        this.mediaCollectionAttributes = mediaCollectionAttributes;
    }

    public void setMediaContainerAttributes(List<String> mediaContainerAttributes) {
        this.mediaContainerAttributes = mediaContainerAttributes;
    }
}
