/*
 * ----------------------------------------------------------------
 * --- WARNING: THIS FILE IS GENERATED AND WILL BE OVERWRITTEN! ---
 * --- Generated at 13 Oct 2024, 22:52:04                       ---
 * ----------------------------------------------------------------
 *  
 * Copyright (c) 2024 SAP SE or an SAP affiliate company. All rights reserved.
 */
package de.hybris.platform.jalo.media;

import de.hybris.platform.directpersistence.annotation.SLDSafe;
import de.hybris.platform.jalo.Item.AttributeMode;
import de.hybris.platform.jalo.media.MediaContainer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Generated class for type CatalogUnawareMediaContainer.
 */
@SLDSafe
@SuppressWarnings({"unused","cast"})
public class CatalogUnawareMediaContainer extends MediaContainer
{
	protected static final Map<String, AttributeMode> DEFAULT_INITIAL_ATTRIBUTES;
	static
	{
		final Map<String, AttributeMode> tmp = new HashMap<String, AttributeMode>(MediaContainer.DEFAULT_INITIAL_ATTRIBUTES);
		DEFAULT_INITIAL_ATTRIBUTES = Collections.unmodifiableMap(tmp);
	}
	@Override
	protected Map<String, AttributeMode> getDefaultAttributeModes()
	{
		return DEFAULT_INITIAL_ATTRIBUTES;
	}
	
}
