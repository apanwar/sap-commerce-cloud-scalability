/**
 *
 */
package com.sap.dto.b2bunit.aop;

import com.sap.dto.b2bunit.service.DtoCacheService;
import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.b2bcommercefacades.company.data.B2BUnitData;
import de.hybris.platform.util.Config;

import org.apache.log4j.Logger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.apache.commons.beanutils.BeanUtils;



/**
 * @author Davide
 *
 */
public class B2BUnitPopulatorAspect
{
	private static final Logger LOG = Logger.getLogger(B2BUnitPopulatorAspect.class);

	private String cacheName;
	private DtoCacheService dtoCacheService;

	public Object populate(final ProceedingJoinPoint joinPoint) throws Throwable
	{
		LOG.debug("Using DTO cache for B2BUnit");
		if (!validatePointCut(joinPoint)){
			LOG.debug("Method sign");
			return joinPoint.proceed();
		}

		if (!Config.getBoolean("dtoB2BUnit.enable", false))
		{
			LOG.debug("DTO cache for B2BUnit is disable, change property dtoB2BUnit.enable");
			return joinPoint.proceed();
		}

		B2BUnitModel model = (B2BUnitModel) joinPoint.getArgs()[0];
		B2BUnitData data = (B2BUnitData) joinPoint.getArgs()[1];

		B2BUnitData cachedData = (B2BUnitData) dtoCacheService.getDtoCached(cacheName, model);
		if (cachedData == null){
			LOG.debug("MISSED Cache fro B2BUnit "+model.getUid());
			Object result = joinPoint.proceed();
			dtoCacheService.storeDtoCached(cacheName, model, data);
			return result;
		}

		BeanUtils.copyProperties(data, cachedData);
		LOG.debug("HIT Cache fro B2BUnit "+model.getUid());
		return null;
	}

	private boolean validatePointCut(final ProceedingJoinPoint joinPoint){

		if (joinPoint.getArgs() == null || joinPoint.getArgs().length != 2)
		{
			LOG.warn("Number of methods arguments is not 2: "+joinPoint.getArgs().length);
			return false;
		}

		if (!(joinPoint.getArgs()[0] instanceof B2BUnitModel) || !(joinPoint.getArgs()[1] instanceof B2BUnitData))
		{
			LOG.warn("Instance types are different: " + joinPoint.getArgs()[0].getClass().getName()+","+
					joinPoint.getArgs()[1].getClass().getName());
			return false;
		}

		return true;
	}

	public void setCacheName(String cacheName) {
		this.cacheName = cacheName;
	}

	public void setDtoCacheService(DtoCacheService dtoCacheService) {
		this.dtoCacheService = dtoCacheService;
	}

}
