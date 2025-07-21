/**
 *
 */
package com.sap.dto.b2bunit.service;

import de.hybris.platform.cache.Cache;
import de.hybris.platform.cache.InvalidationListener;
import de.hybris.platform.cache.InvalidationManager;
import de.hybris.platform.cache.InvalidationTarget;
import de.hybris.platform.cache.InvalidationTopic;
import de.hybris.platform.cache.RemoteInvalidationSource;
import de.hybris.platform.core.PK;
import de.hybris.platform.core.Registry;
import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.regioncache.CacheValueLoader;
import de.hybris.platform.regioncache.key.CacheKey;
import de.hybris.platform.regioncache.region.CacheRegion;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.site.BaseSiteService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;

import de.hybris.platform.util.Config;
import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Logger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;


/**
 * @author Davide
 *
 */
public class DefaultDtoCacheService implements DtoCacheService
{
	private final Logger LOG = Logger.getLogger(DefaultDtoCacheService.class);

	private List<CacheRegion> cacheRegions;
	private Map<String, CacheRegion> mapCacheRegions;
	private CommonI18NService commonI18NService;
	private Set<String> invalidationDeploymentCodes;
	private BaseSiteService baseSiteService;


	private static boolean initInvlidation = false;


	@PostConstruct
	public void init()
	{
		final Map<String, CacheRegion> tmpMapCacheRegions = new HashMap<String, CacheRegion>();
		for (final CacheRegion region : cacheRegions)
		{
			tmpMapCacheRegions.put(region.getName(), region);
		}
		mapCacheRegions = tmpMapCacheRegions;
		initDtoCacheInvalidation();
	}


	private void initDtoCacheInvalidation()
	{
		if (initInvlidation)
		{
			return;
		}

		final InvalidationTopic topic = InvalidationManager.getInstance().getInvalidationTopic(new String[]
		{ Cache.CACHEKEY_HJMP, Cache.CACHEKEY_ENTITY });

		topic.addInvalidationListener(new InvalidationListener()
		{

			@Override
			public void keyInvalidated(final Object[] key, final int invalidationType, final InvalidationTarget target,
					final RemoteInvalidationSource remoteSrc)
			{

				if (!Config.getBoolean("dtoCacheService.invalidation.enable", false))
				{
					LOG.debug("Invalidation Cache is disable, change property dtoCacheService.invalidation.enable");
					return;
				}

				if (key.length < 4)
				{
					return;
				}

				if (!(key[2] instanceof String))
				{
					return;
				}


				if (!(key[3] instanceof PK))
				{
					return;
				}

				final String deploymentCode = (String) key[2];
				if (!invalidationDeploymentCodes.contains(deploymentCode))
				{
					return;
				}

				final PK pk = (PK) key[3];
				LOG.debug("Invalidationg object "+pk+" for deployment code "+deploymentCode);

				final CacheKey cacheKey = getCacheKey(pk);
				for (final CacheRegion region : mapCacheRegions.values())
				{
					region.remove(cacheKey, false);
				}

			}

		});

	}

	@Override
	public void invalidCache(final PK pk)
	{
		final CacheKey cacheKey = getCacheKey(pk);
		LOG.debug("Invalidating Item " + pk + " from CacheRegions");
		for (final CacheRegion region : mapCacheRegions.values())
		{
			region.remove(cacheKey, false);
		}

	}


	@Override
	public boolean canApplyDtoCacheOnPopulator(final String cacheName)
	{
		return mapCacheRegions.containsKey(cacheName);
	}


	@SuppressWarnings("unchecked")
	@Override
	public Object getDtoCached(final String cacheName, final ItemModel source)
	{
		if (!canApplyDtoCacheOnPopulator(cacheName))
		{
			return null;
		}

		final CacheRegion region = mapCacheRegions.get(cacheName);
		final CacheKey key = getCacheKey(source.getPk());
		final String mapKey = getMapKeyPrefix();

		final Map<String, Object> mapObject = (Map<String, Object>) region.get(key);
		if (mapObject == null)
		{
			return null;
		}

		return mapObject.get(mapKey);
	}

	@SuppressWarnings("unchecked")
	@Override
	public Object getDtoCached(final String cacheName, final ItemModel source, final String options)
	{
		if (!canApplyDtoCacheOnPopulator(cacheName))
		{
			return null;
		}

		final CacheRegion region = mapCacheRegions.get(cacheName);
		final CacheKey key = getCacheKey(source.getPk());
		final String mapKey = getMapKeyPrefix() + options;

		final Map<String, Object> mapObject = (Map<String, Object>) region.get(key);
		if (mapObject == null)
		{
			return null;
		}

		return mapObject.get(mapKey);
	}



	@Override
	public void storeDtoCached(final String cacheName, final ItemModel source, final Object target)
	{
		final CacheRegion region = mapCacheRegions.get(cacheName);
		final CacheKey key = getCacheKey(source.getPk());

		final String mapKey = getMapKeyPrefix();

		final Map<String, Object> mapObject = getMapFromCache(key, region);
		mapObject.put(mapKey, target);
		region.remove(key, false);

		final CacheValueLoader<Object> loader = new CacheValueLoader<Object>()
		{
			@Override
			public Object load(final CacheKey key)
			{
				return mapObject;
			}
		};

		region.getWithLoader(key, loader);
	}

	@Override
	public void storeDtoCached(final String cacheName, final String options, final ItemModel source, final Object target)
	{
		final CacheRegion region = mapCacheRegions.get(cacheName);
		final CacheKey key = getCacheKey(source.getPk());
		final String mapKey = getMapKeyPrefix() + options;

		final Map<String, Object> mapObject = getMapFromCache(key, region);
		mapObject.put(mapKey, target);
		region.remove(key, false);

		final CacheValueLoader<Object> loader = new CacheValueLoader<Object>()
		{
			@Override
			public Object load(final CacheKey key)
			{
				return mapObject;
			}
		};

		region.getWithLoader(key, loader);
	}

	protected String getMapKeyPrefix()
	{
		final StringBuffer sb = new StringBuffer();

		if (baseSiteService.getCurrentBaseSite() == null)
		{
			sb.append("noSite|");
		}
		else
		{
			sb.append(baseSiteService.getCurrentBaseSite().getUid() + "|");
		}

		final String language = commonI18NService.getCurrentLanguage().getIsocode();
		sb.append(language + "|");

		return sb.toString();
	}

	@Aspect
	@SuppressWarnings("unused")
	public static class AspectDestroyEHcacheRegion
	{

		@Around("de.hybris.platform.regioncache.region.impl.EHCacheRegion.destroy()")
		public Object doBasicProfiling(final ProceedingJoinPoint pjp) throws Throwable
		{

			return null;
		}

		@Around("execution(* de.hybris.platform.regioncache.region.impl.EHCacheRegion.destroy(..))")
		public Object destroy(final ProceedingJoinPoint pjp) throws Throwable
		{
			return null;
		}

	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getMapFromCache(final CacheKey key, final CacheRegion region)
	{
		Map<String, Object> mapObject = (Map<String, Object>) region.get(key);
		if (mapObject == null)
		{
			mapObject = new HashMap<String, Object>();
		}
		return mapObject;
	}

	protected CacheKey getCacheKey(final PK key)
	{
		return new PKCacheKey(key);
	}

	public void setCacheRegions(final List<CacheRegion> cacheRegions)
	{
		this.cacheRegions = cacheRegions;
	}

	public void setCommonI18NService(final CommonI18NService commonI18NService)
	{
		this.commonI18NService = commonI18NService;
	}


	public void setInvalidationDeploymentCodes(final Set<String> invalidationDeploymentCodes)
	{
		this.invalidationDeploymentCodes = invalidationDeploymentCodes;
	}





	@Override
	public void invalidCache(final String cacheName)
	{
		CacheRegion cacheRegion = mapCacheRegions.get(cacheName);
		if (cacheRegion != null)
		{
			cacheRegion.clearCache();
			return;
		}

		cacheRegion = (CacheRegion) Registry.getCoreApplicationContext().getBean(cacheName);
		if (cacheRegion != null)
		{
			cacheRegion.clearCache();
		}
	}


	public void setBaseSiteService(final BaseSiteService baseSiteService)
	{
		this.baseSiteService = baseSiteService;
	}






}
