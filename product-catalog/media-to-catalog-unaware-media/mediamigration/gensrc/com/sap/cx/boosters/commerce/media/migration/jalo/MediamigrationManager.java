/*
 * ----------------------------------------------------------------
 * --- WARNING: THIS FILE IS GENERATED AND WILL BE OVERWRITTEN! ---
 * --- Generated at 13 Oct 2024, 22:52:04                       ---
 * ----------------------------------------------------------------
 */
package com.sap.cx.boosters.commerce.media.migration.jalo;

import com.sap.cx.boosters.commerce.media.migration.constants.MediamigrationConstants;
import de.hybris.platform.directpersistence.annotation.SLDSafe;
import de.hybris.platform.jalo.Item;
import de.hybris.platform.jalo.Item.AttributeMode;
import de.hybris.platform.jalo.JaloBusinessException;
import de.hybris.platform.jalo.JaloSession;
import de.hybris.platform.jalo.JaloSystemException;
import de.hybris.platform.jalo.SessionContext;
import de.hybris.platform.jalo.extension.Extension;
import de.hybris.platform.jalo.extension.ExtensionManager;
import de.hybris.platform.jalo.media.CatalogUnawareMediaContainer;
import de.hybris.platform.jalo.type.ComposedType;
import de.hybris.platform.jalo.type.JaloGenericCreationException;
import java.util.HashMap;
import java.util.Map;

/**
 * Generated class for type <code>MediamigrationManager</code>.
 */
@SuppressWarnings({"unused","cast"})
@SLDSafe
public class MediamigrationManager extends Extension
{
	protected static final Map<String, Map<String, AttributeMode>> DEFAULT_INITIAL_ATTRIBUTES;
	static
	{
		final Map<String, Map<String, AttributeMode>> ttmp = new HashMap();
		DEFAULT_INITIAL_ATTRIBUTES = ttmp;
	}
	@Override
	public Map<String, AttributeMode> getDefaultAttributeModes(final Class<? extends Item> itemClass)
	{
		Map<String, AttributeMode> ret = new HashMap<>();
		final Map<String, AttributeMode> attr = DEFAULT_INITIAL_ATTRIBUTES.get(itemClass.getName());
		if (attr != null)
		{
			ret.putAll(attr);
		}
		return ret;
	}
	
	public CatalogUnawareMediaContainer createCatalogUnawareMediaContainer(final SessionContext ctx, final Map attributeValues)
	{
		try
		{
			ComposedType type = getTenant().getJaloConnection().getTypeManager().getComposedType("CatalogUnawareMediaContainer");
			return (CatalogUnawareMediaContainer)type.newInstance( ctx, attributeValues );
		}
		catch( JaloGenericCreationException e)
		{
			final Throwable cause = e.getCause();
			throw (cause instanceof RuntimeException ?
			(RuntimeException)cause
			:
			new JaloSystemException( cause, cause.getMessage(), e.getErrorCode() ) );
		}
		catch( JaloBusinessException e )
		{
			throw new JaloSystemException( e ,"error creating CatalogUnawareMediaContainer : "+e.getMessage(), 0 );
		}
	}
	
	public CatalogUnawareMediaContainer createCatalogUnawareMediaContainer(final Map attributeValues)
	{
		return createCatalogUnawareMediaContainer( getSession().getSessionContext(), attributeValues );
	}
	
	public static final MediamigrationManager getInstance()
	{
		ExtensionManager em = JaloSession.getCurrentSession().getExtensionManager();
		return (MediamigrationManager) em.getExtension(MediamigrationConstants.EXTENSIONNAME);
	}
	
	@Override
	public String getName()
	{
		return MediamigrationConstants.EXTENSIONNAME;
	}
	
}
