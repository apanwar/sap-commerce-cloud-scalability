package com.sap.cx.boosters.azureoptimizationsreadonly.aspect;

import de.hybris.platform.jalo.JaloSession;
import de.hybris.platform.jalo.SessionContext;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import org.apache.commons.configuration.Configuration;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static de.hybris.platform.jalo.flexiblesearch.internal.ReadOnlyConditionsHelper.CTX_ENABLE_FS_ON_READ_REPLICA;

@Aspect
public class ReadOnlyReplicaAspect {
    private static final Logger LOG = LoggerFactory.getLogger(ReadOnlyReplicaAspect.class);

    private boolean enabled;
    private Set<String> enabledMethods;

    @Around("execution(* de.hybris.platform.adaptivesearch.converters.populators.AsConfigurableSearchConfigurationPopulator.populate(..))")
    public Object aroundAsConfigurableSearchConfigurationPopulatorPopulate(final ProceedingJoinPoint pjp) throws Throwable {
        return activateReadReplicaIfNeeded("AsConfigurableSearchConfigurationPopulator.populate", pjp);
    }

    @Around("execution(* de.hybris.platform.servicelayer.media.impl.DefaultMediaDao.findMediasByFormatQualifiers(..))")
    public Object aroundDefaultMediaDaoFindMediasByFormatQualifiers(final ProceedingJoinPoint pjp) throws Throwable {
        return activateReadReplicaIfNeeded("DefaultMediaDao.findMediasByFormatQualifiers", pjp);
    }

    @Around("execution(* de.hybris.platform.servicelayer.media.impl.DefaultMediaDao.findMediaByFormat(..))")
    public Object aroundDefaultMediaDaoFindMediasByFormat(final ProceedingJoinPoint pjp) throws Throwable {
        return activateReadReplicaIfNeeded("DefaultMediaDao.findMediaByFormat", pjp);
    }

    @Around("execution(* de.hybris.platform.category.impl.DefaultCategoryService.getPathsForCategory(..))")
    public Object aroundDefaultCategoryServiceGetPathsInternal(final ProceedingJoinPoint pjp) throws Throwable {
        return activateReadReplicaIfNeeded("DefaultCategoryService.getPathsInternal", pjp);
    }

    @Around("execution(* de.hybris.platform.commerceservices.category.impl.DefaultCommerceCategoryService.getPathsForCategory(..))")
    public Object aroundDefaultCommerceCategoryServiceGetPathsInternal(final ProceedingJoinPoint pjp) throws Throwable {
        return activateReadReplicaIfNeeded("DefaultCommerceCategoryService.getPathsInternal", pjp);
    }

    private Object activateReadReplicaIfNeeded(final String method, final ProceedingJoinPoint pjp) throws Throwable {
        Object proceedResult;

        if (enabled && enabledMethods.contains(method)) {
            try {
                final SessionContext ctx = JaloSession.getCurrentSession().createLocalSessionContext();
                ctx.setAttribute(CTX_ENABLE_FS_ON_READ_REPLICA, true);
                LOG.debug("ReadReplicaAspect activated read-replica data source for {}", method);
                proceedResult = pjp.proceed();
            } finally {
                JaloSession.getCurrentSession().removeLocalSessionContext();
                LOG.debug("ReadReplicaAspect deactivated read-replica data source after {} execution", method);
            }
        } else {
            proceedResult = pjp.proceed();
        }

        return proceedResult;
    }

    public void setConfigurationService(final ConfigurationService configurationService) {
        final Configuration configuration = configurationService.getConfiguration();

        enabled = configuration.getBoolean("azureoptimizationsreadonly.read-replica-aspect.enabled", false);
        if (enabled) {
            enabledMethods = new HashSet<>();
            for (final Iterator<String> keys = configuration.getKeys("azureoptimizationsreadonly.read-replica-aspect.enabled.methods"); keys.hasNext(); ) {
                enabledMethods.add(configuration.getString(keys.next()));
            }
        }
    }
}
