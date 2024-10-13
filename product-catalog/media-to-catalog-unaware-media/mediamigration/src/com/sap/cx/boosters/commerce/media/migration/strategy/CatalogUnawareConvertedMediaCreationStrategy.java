package com.sap.cx.boosters.commerce.media.migration.strategy;

import de.hybris.platform.catalog.model.CatalogUnawareMediaModel;
import de.hybris.platform.core.model.media.MediaModel;
import de.hybris.platform.mediaconversion.conversion.DefaultConvertedMediaCreationStrategy;
import de.hybris.platform.mediaconversion.model.ConversionMediaFormatModel;
import de.hybris.platform.servicelayer.exceptions.ModelNotFoundException;
import de.hybris.platform.servicelayer.media.MediaIOException;
import org.apache.log4j.Logger;

import java.io.InputStream;

public class CatalogUnawareConvertedMediaCreationStrategy extends DefaultConvertedMediaCreationStrategy {

    private static final Logger LOG = Logger.getLogger(CatalogUnawareConvertedMediaCreationStrategy.class);

    @Override
    public MediaModel createOrUpdate(final MediaModel parent, final ConversionMediaFormatModel format, final InputStream content)
            throws MediaIOException {

        if (parent instanceof CatalogUnawareMediaModel) {

            MediaModel dmm;
            try {
                dmm = this.getMediaService().getMediaByFormat(parent.getMediaContainer(), format);
                LOG.debug("Updating existing media '" + dmm + "'.");
            } catch (final ModelNotFoundException e) {
                dmm = this.getModelService().create(CatalogUnawareMediaModel.class);
                dmm.setCode(this.createCode(parent, format));
                dmm.setFolder(parent.getFolder());
                dmm.setMediaContainer(parent.getMediaContainer());
                dmm.setMediaFormat(format);

                // additional
                dmm.setAltText(parent.getAltText());
                dmm.setDescription(parent.getDescription());
            }

            dmm.setOriginal(parent);
            dmm.setOriginalDataPK(parent.getDataPK());
            this.getModelService().save(dmm);

            this.loadContents(dmm, parent, format, content);
            this.getModelService().refresh(dmm);

            return dmm;
        } else {
            return super.createOrUpdate(parent, format, content);
        }
    }
}
