# Migrating Product Media to Catalog Aware Media

Please refer to the [blog post](https://community.sap.com/t5/crm-and-cx-blogs-by-sap/enhancing-scalability-of-your-product-catalog-with-catalog-unaware-media/ba-p/13762371) to understand the background and the scenarios where it can be useful.

## How to Migrate
### Add CatalogUnawareMediaContainer
The first step for the migration is to introduce `CatalogUnawareMediaContainer` item type. The standard item type of the media container is catalog aware. Since, multiple media containers cannot own a single media. It becomes impossible to refer the same media in the media containers of two or more catalog versions. Hence, introduction of a `CatalogUnawareMediaContainer` is a must for this migration. To create this do the following:
1. Create the `CatalogUnawareMediaContainer` item type using the following item definition:
    ```xml
    <itemtype code="CatalogUnawareMediaContainer" autocreate="true" generate="true" extends="Media"
                    jaloclass="de.hybris.platform.jalo.media.CatalogUnawareMediaContainer">
        <custom-properties>
            <property name="catalogItemType"><value>java.lang.Boolean.FALSE</value></property>
        </custom-properties>
        <attributes>
            <attribute qualifier="catalogVersion" type="CatalogVersion" redeclare="true">
                <persistence type="property"/>
                <modifiers optional="true" initial="false" />
            </attribute>
        </attributes>
    </itemtype>
    ```
2. Execute the `System Update` to ensure that the `CatalogUnawareMediaContainer` is available to your SAP Commerce persistence.

After this, proceed ahead with the migration script.

### Migration of Media to Catalog Unaware Media & Media Container to Catalog Unaware Media Container
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
