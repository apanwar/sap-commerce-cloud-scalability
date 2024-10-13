package com.sap.cx.boosters.commerce.media.migration.strategy;

import de.hybris.platform.catalog.model.CatalogUnawareMediaModel;
import de.hybris.platform.core.model.media.MediaModel;
import de.hybris.platform.mediaconversion.conversion.DefaultConvertedMediaCreationStrategy;

public class CatalogUnawareConvertedMediaCreationStrategy extends DefaultConvertedMediaCreationStrategy {

    @Override
    protected MediaModel createModel() {
        return this.getModelService().create(CatalogUnawareMediaModel.class);
    }
}
