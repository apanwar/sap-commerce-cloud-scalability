# Migrating Product Media to Catalog Aware Media

Please refer to the [blog post](https://community.sap.com/t5/crm-and-cx-blogs-by-sap/enhancing-scalability-of-your-product-catalog-with-catalog-unaware-media/ba-p/13762371)

## How to Migrate
The [ImpEx script](https://github.com/apanwar/sap-commerce-cloud-scalability/blob/main/product-catalog/media-to-catalog-unaware-media/impex/cron-jobs.impex) is a sample to migrates the media of `apparelProductCatalog` to CatalogAware Media. You can import this script to create the cron job and then manually execute it during the quiet business hours,

You can adjust the script for your usage by:
- Updating the `id` and `version` of your online catalog version.
- Updating the `Media` attributes of your product item model
- Updating the `MediaCollection` attributes of your product item model
- Updating to `MediaContainer` attributes of your product item model

Further feel free to enhance the script as per your need.


>**Disclaimers**
> - This script creates a sample migration cron job that on execute migrates the media of `apparelProductCatelog`. This script has not been tested extensively on a productive environment. This is recommended to test the script extensively prior to execute it on a productive environment.
> - This is recommended to execute such a migration in Off-Peak hours as this migration may impact customer experience.
> - This is a reference created to share the knowledge based on the experience from past engagements. There is no dedicated support available on this script, neither from the author and not from SAP.
