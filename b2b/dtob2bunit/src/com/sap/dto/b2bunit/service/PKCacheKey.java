/*
 * [y] hybris Platform
 *
 * Copyright (c) 2000-2017 SAP SE
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of SAP
 * Hybris ("Confidential Information"). You shall not disclose such
 * Confidential Information and shall use it only in accordance with the
 * terms of the license agreement you entered into with SAP Hybris.
 */
package com.sap.dto.b2bunit.service;

import de.hybris.platform.core.PK;
import de.hybris.platform.core.Registry;
import de.hybris.platform.regioncache.key.CacheUnitValueType;

import java.io.Serializable;


public class PKCacheKey implements de.hybris.platform.regioncache.key.CacheKey, Serializable
{

	private PK pk;


	public PKCacheKey()
	{
		//
	}

	public PKCacheKey(final PK pk)
	{
		this.pk = pk;
	}

	@Override
	public CacheUnitValueType getCacheValueType()
	{
		return CacheUnitValueType.SERIALIZABLE;
	}


	@Override
	public String getTenantId()
	{
		return Registry.getCurrentTenant().getTenantID();
	}


	@Override
	public Object getTypeCode()
	{
		return pk;
		//		if (de.hybris.platform.util.Config.getBoolean("dto.cache.hac.statistics.enable", false))
		//		{
		//			return pk;
		//		}
		//		else
		//		{
		//			return "CacheKey";
		//		}
	}

	public PK getPk()
	{
		return pk;
	}


	public void setPk(final PK pk)
	{
		this.pk = pk;
	}


	@Override
	public int hashCode()
	{
		final int prime = 31;
		int result = 1;
		result = prime * result + ((pk == null) ? 0 : pk.hashCode());
		return result;
	}

	@Override
	public boolean equals(final Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		if (obj == null)
		{
			return false;
		}
		if (getClass() != obj.getClass())
		{
			return false;
		}
		final PKCacheKey other = (PKCacheKey) obj;
		if (pk == null)
		{
			if (other.pk != null)
			{
				return false;
			}
		}
		else if (!pk.equals(other.pk))
		{
			return false;
		}
		return true;
	}



}
