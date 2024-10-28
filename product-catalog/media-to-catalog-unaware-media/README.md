# Migrating Product Media to Catalog Aware Media

Please refer to the [blog post](https://community.sap.com/t5/crm-and-cx-blogs-by-sap/enhancing-scalability-of-your-product-catalog-with-catalog-unaware-media/ba-p/13762371) to understand the background and the scenarios where it can be useful.

## Introducing CatalogUnawareMediaContainer
The first step for the using `CatalogUnawareMedia` for products is to introduce `CatalogUnawareMediaContainer` item type to support it with `gallery` images. The standard item type of the media container is catalog aware. Since, multiple media containers cannot own a single media. It becomes impossible to refer the same media in the media containers of two or more catalog versions. Hence, introduction of a `CatalogUnawareMediaContainer` is a must for this migration. To create this do the following:
1. Create the `CatalogUnawareMediaContainer` item type using the following item definition:
    ```xml
    <itemtype code="CatalogUnawareMediaContainer" autocreate="true" generate="true" extends="MediaContainer"
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

After this, proceed ahead with configuration of the migration cron job.

## Configuring the Cron job for Migration of Media to Catalog Unaware Media & Media Container to Catalog Unaware Media Container
The [ImpEx script](./mediamigration/resources/impex/essentialdata_mediamigration.impex) is a sample script as commented to create a cron job to migrate the media of `apparelProductCatalog` to CatalogAware Media. You can adjust this ImpEx script to create the cron job and then execute it during the quiet business hours with following adjustments:
- Batch size to pick how many products to pick in one batch.
- Migration workers to configure how many worker threads to use for migration.
- Reference catalog version to configura the catalog version that has all accurate medias associated across the product catalog versions.

<p>
<img src="cron-job-configurations.png" style="border: 2px;">
<u><strong>Figure </strong> | Migration Cron Job Configurations</u>
</p>


## Optimizations at source integration / user experience
### Backoffice
If you like to simplify the backoffice user interface to avoid mistakes from the business users. You can update the backoffice configurations of product editor area to ensure that the business user creates the `CatalogUnawareMedia` and `CatalogUnawareMediaContainer` during product enrichments.

### Integrations
If there are automations / integrations to create the product media for example: ImpEx, Cloud Hot Folders, etc; It is recommended that you correct the headers to ensure that the Media is created for product as `CatalogUnawareMedia` and the media container is created as `CatalogUnawareMediaContainer`.

### Media Conversion
The extension covers you if youn are using the media conversion functionality. With this the regular Media converts in the disired formats as regular media and the Catalog Unaware Media converts in the disired formats as catalog unaware media.

>**Note 2:** As an alternative, You can use the mediamigration extension available [here](./mediamigration), followed by a system update and configurion & execution of the `CatalogAwareToCatalogUnawareMediaMigrationCronJob` cron job with job as catalogAwareToCatalogUnawareMediaMigrationJob`.



>**Disclaimers**
> - This extension creates a sample migration cron job (if uncommented) that on execute migrates the media of `apparelProductCatelog`. This is recommended to test the migration extensively to identify appropriate configurations like batch size and number of workers, prior to execute it on a productive environment.
> - This is recommended to execute such a migration in Off-Peak hours as this migration may impact customer experience.
> - This is a reference created to share the knowledge based on the experience from past engagements. There is no dedicated support available on this script, neither from the author and not from SAP.
