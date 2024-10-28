package impex.test

import de.hybris.platform.catalog.model.synchronization.CatalogVersionSyncJobModel

def createJobIdentifier(def sourceCatalogVersion, def targetCatalogVersion) {
    return "sync ${sourceCatalogVersion.catalog.id}:${sourceCatalogVersion.version}->${targetCatalogVersion.catalog.id}:${targetCatalogVersion.version}"
}

def createProductCatalogSyncJob(def sourceCatalogVersion, def targetCatalogVersion) {
    def jobIdentifier = createJobIdentifier(sourceCatalogVersion, targetCatalogVersion)
    if (catalogSynchronizationService.getSyncJob(sourceCatalogVersion, targetCatalogVersion, jobIdentifier) == null) {

        final def syncJob = modelService.create(CatalogVersionSyncJobModel)
        syncJob.setCode(jobIdentifier)
        syncJob.setSourceVersion(sourceCatalogVersion)
        syncJob.setTargetVersion(targetCatalogVersion)
        syncJob.setCreateNewItems(Boolean.TRUE)
        syncJob.setRemoveMissingItems(Boolean.TRUE)
        modelService.save(syncJob)

        setupSyncJobService.processRootTypes(syncJob, sourceCatalogVersion.catalog.id, productCatalogSyncRootTypeCodes)
        setupSyncJobService.processEditSyncAttributeDescriptors(syncJob, sourceCatalogVersion.catalog.id, productCatalogEditSyncDescriptors)
    }
}

def masterCatalog = 'masterProductCatalog'
def firstChildCatalog = 'firstChildProductCatalog'
def secondChildCatalog = 'secondChildProductCatalog'

setupSyncJobService.createProductCatalogSyncJob(firstChildCatalog)
setupSyncJobService.createProductCatalogSyncJob(secondChildCatalog)

def masterProductStaged = catalogVersionService.getCatalogVersion(masterCatalog, 'Staged')
def firstChildProductStaged = catalogVersionService.getCatalogVersion(firstChildCatalog, 'Staged')
def secondChildProductStaged = catalogVersionService.getCatalogVersion(secondChildCatalog, 'Staged')

createProductCatalogSyncJob(masterProductStaged, firstChildProductStaged)
createProductCatalogSyncJob(masterProductStaged, secondChildProductStaged)