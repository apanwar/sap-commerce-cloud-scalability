# Testing with Test Setup

To test the media migration along with media conversion, do the following:

1. Import the `testdata_mediamigration.impex`. This will create a setup of a master product catalog with staged version and two children catalogs with staged and online versions.
2. To set up the catalog synchronization between these catalogs, execute the `test-catalog-sync-jobs.groovy` via hAC with `COMMIT` mode enabled.
3. Convert the medias using `Image Magick`:
   1. Go to Catalog -> Products -> Search `DV3337-101`
   2. Go to Editor Area -> `Multimedia` tab
   3. Double-click on the attribute value of `Gallery Images` attribute to Open the media container.
   4. Go to Medias attribute and click on `Convert Missing Medias`
   5. Validate if medias are converted.
4. Execute the following catalog synchronizations:
   1. Master Product Catalog : Staged -> First Child Product Catalog : Staged
   2. Master Product Catalog : Staged -> Second Child Product Catalog : Staged
   3. First Child Product Catalog : Staged -> First Child Product Catalog : Online
   4. Second Child Product Catalog : Staged -> Second Child Product Catalog : Online
5. Validate your catalog setup along with media and media containers.
6. Execute the cron job `productCatalogAwareMediaToCatalogAwareMediaMigrationCronJob`
7. Validate of the medias and media containers associated to the products in all the catalog versions are catalog unaware.