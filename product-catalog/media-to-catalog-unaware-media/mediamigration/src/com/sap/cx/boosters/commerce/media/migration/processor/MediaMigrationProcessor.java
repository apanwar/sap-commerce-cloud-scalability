package com.sap.cx.boosters.commerce.media.migration.processor;

import de.hybris.platform.core.model.product.ProductModel;

public interface MediaMigrationProcessor {

    void process(ProductModel product);

}