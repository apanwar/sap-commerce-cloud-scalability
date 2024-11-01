/*
 * Copyright (c) 2021 SAP SE or an SAP affiliate company. All rights reserved.
 */
package de.hybris.platform.persistence;

import com.google.common.base.Preconditions;
import de.hybris.platform.cache.AbstractCacheUnit;
import de.hybris.platform.cache.Cache;
import de.hybris.platform.core.*;
import de.hybris.platform.core.locking.ItemLockedForProcessingException;
import de.hybris.platform.jalo.JaloSession;
import de.hybris.platform.jalo.SessionContext;
import de.hybris.platform.jalo.flexiblesearch.internal.ReadOnlyConditionsHelper;
import de.hybris.platform.jdbcwrapper.PreparedStatementImpl;
import de.hybris.platform.persistence.audit.AuditableOperation;
import de.hybris.platform.persistence.audit.Operation;
import de.hybris.platform.persistence.framework.PersistencePool;
import de.hybris.platform.persistence.hjmp.*;
import de.hybris.platform.persistence.property.*;
import de.hybris.platform.persistence.property.JDBCValueMappings.ValueReader;
import de.hybris.platform.persistence.property.JDBCValueMappings.ValueWriter;
import de.hybris.platform.persistence.type.ComposedTypeRemote;
import de.hybris.platform.tx.Transaction;
import de.hybris.platform.util.Config;
import de.hybris.platform.util.EJBTools;
import de.hybris.platform.util.jeeapi.YFinderException;
import de.hybris.platform.util.jeeapi.YNoSuchEntityException;
import de.hybris.platform.util.jeeapi.YObjectNotFoundException;
import de.hybris.platform.util.typesystem.PlatformStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import java.sql.*;
import java.util.Date;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static de.hybris.platform.persistence.audit.AuditableOperations.aboutToExecute;


public class GenericBMPBean extends GenericItemEJB {
    private static final Logger LOGGER = Logger.getLogger(GenericBMPBean.class.getName());
    private static final boolean LOG = "true".equals(System.getProperty("hjmp.log"));
    private static final boolean LOG_ATTRIBUTEACCESS = "true".equals(System.getProperty("hjmp.log.attributeaccess"));
    private static final boolean LOG_MODIFICATIONS = false;
    private static final boolean CHECK_VALUE_DIFFERENCES = Config.getBoolean("hjmp.write.changes.only", true);

    private static final int READER = 0;
    private static final int WRITER = 1;
    private static final Object[] DATE_RW = new Object[]
            {JDBCValueMappings.getInstance().getValueReader(java.util.Date.class),
                    JDBCValueMappings.getInstance().getValueWriter(java.util.Date.class)};
    @SuppressWarnings("unused")
    private static final Object[] STRING_RW = new Object[]
            {JDBCValueMappings.getInstance().getValueReader(java.lang.String.class),
                    JDBCValueMappings.getInstance().getValueWriter(java.lang.String.class)};
    private static final Object[] LONG_RW = new Object[]
            {JDBCValueMappings.getInstance().getValueReader(long.class), JDBCValueMappings.getInstance().getValueWriter(
                    long.class)};
    private static final Object[] PK_RW = new Object[]
            {JDBCValueMappings.getInstance().getValueReader(PK.class), JDBCValueMappings.getInstance().getValueWriter(
                    PK.class)};

    // ----------------------------------------------------------------------------

    private int ejbCreateNestedCounter = 0;
    private boolean beforeCreate = false;
    private GenericItemEntityState entityState = null;
    private boolean localInvalidationAdded = false;

    private ItemDeployment getDeployment() {
        return getEntityContext().getItemDeployment();
    }

    private PersistencePool getPersistencePool() {
        return getEntityContext().getPersistencePool();
    }


    private String getTable() {
        return getDeployment().getDatabaseTableName();
    }

    private int getTypeCode() {
        return getDeployment().getTypeCode();
    }

    private String getDumptable() {
        return getDeployment().getDumpPropertyTableName();
    }

    private void clearFields() {
        localInvalidationAdded = false;
        setEntityState(null);
        if (ejbCreateNestedCounter != 0) {
            if (LOG) {
                LOGGER.debug("ejbCreateNestedCounter is " + ejbCreateNestedCounter);
            }
            ejbCreateNestedCounter = 0;
        }
    }

    @Override
    public long getHJMPTS() {
        return getEntityState().hjmpTS;
    }

    /**
     * @throws YNoSuchEntityException is there is no item for the given pk
     */
    @Override
    public void ejbLoad() {
        clearFields();
        loadData();
        super.ejbLoad();
    }

    private String getID() {
        return Integer.toHexString(System.identityHashCode(this));
    }

    public final GenericItemEntityState getEntityState() {
        if (entityState == null) {
            throw new HJMPException("no entity state available for " + entityContext.getPK());
        }
        return entityState;
    }

    private void invalidate(final boolean success, final int type) {
        final Transaction tx = Transaction.current();

        final PK thePK = getEntityState().getPK();
        final int invType = type;
        final Object[] key = new Object[]
                {Cache.CACHEKEY_HJMP, Cache.CACHEKEY_ENTITY, thePK.getTypeCodeAsString(), thePK};

        if (tx.isRunning()) // tx running -> just invalidate
        {
            tx.invalidate(key, 3, invType);
        } else {
            if (success) {
                tx.invalidateAndNotifyCommit(key, 3, invType); // tx not running -> invalidate and notify commit in one go
            } else {
                tx.invalidateAndNotifyRollback(key, 3, invType);// tx not running -> invalidate and notify rollback in one go
            }
        }
    }

    private void setEntityState(final GenericItemEntityState newEntityState) {
        if (newEntityState != null) {
            setNeedsStoring(true);
        }

        entityState = newEntityState;

        if (ejbCreateNestedCounter == 0) {
            addLocalRollbackInvalidation(false);
        }
    }

    private void addLocalRollbackInvalidation(final boolean remove) {
        if (!localInvalidationAdded && entityState != null && entityState.isTransactionBound && Transaction.current().isRunning()) {
            localInvalidationAdded = true;
            final Object[] key = new Object[]
                    {Cache.CACHEKEY_HJMP, Cache.CACHEKEY_ENTITY, PlatformStringUtils.valueOf(getDeployment().getTypeCode()),
                            entityContext.getPK()};
            Transaction.current().addToDelayedRollbackLocalInvalidations(key,
                    remove ? AbstractCacheUnit.INVALIDATIONTYPE_REMOVED : AbstractCacheUnit.INVALIDATIONTYPE_MODIFIED,
                    3);
        }
    }

    /**
     * @throws YNoSuchEntityException is there is no item for the given pk
     */
    private void loadData() {
        final PK pk = entityContext.getPK();
        Preconditions.checkArgument(pk != null);
        final GenericItemEntityState state = GenericItemEntityState
                .getInstance(getPersistencePool().getCache(), pk, getDeployment()).getEntityState();
        if (LOG) {
            LOGGER.debug("EJB loading " + pk + " to " + getID() + ": " + state.toDetailedString());
        }
        if (state == null) {
            int typeCode = 0;
            String name = null;
            String dbTable = null;
            if (getDeployment() != null) {
                try {
                    typeCode = getDeployment().getTypeCode();
                    name = getDeployment().getName();
                    dbTable = getDeployment().getDatabaseTableName();
                } catch (final Exception e) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(e.getMessage());
                    }
                }
            }
            String errorMsg = "";
            errorMsg = "Entity not found ( pk = " + pk + " " + ((name != null && name.length() > 0) ? "name = '" + name + "' " : "")
                    + (typeCode != 0 ? "type code = '" + typeCode + "' " : "")
                    + ((dbTable != null && dbTable.length() > 0) ? "db table = '" + dbTable + "'" : "") + ")";
            throw new YNoSuchEntityException(errorMsg, pk);
        }
        setEntityState(state);
        setNeedsStoring(false);
    }

    @Override
    @SuppressWarnings("all")
    public void ejbStore() {
        final PK pk = getPkString();

        if (LOG || (LOG_MODIFICATIONS && needsStoring())) {
            LOGGER.debug("EJB storing " + pk + " from " + getID() + " (tx-bound=" + needsStoring() + ")");
        }
        if (needsStoring()) {
            final GenericItemEntityState entityState = getEntityState();

            final boolean fieldsChanged = entityState.hasChangedFields();
            final boolean cachesChanged = hasModifiedCaches();

            checkOptimisticLockVersionIsValidForServiceLayer(entityState);

            if (fieldsChanged || cachesChanged) {
                boolean success = false;
                final AuditableOperation audit = aboutToExecute(Operation.update(getPkString(), getTypePkString()));

                try {
                    super.ejbStore();
                    // save timestamps
                    setModifiedTimestamp(new Date());
                    // save caches
                    if (cachesChanged) {
                        storeCaches();
                    }
                    super.ejbStore();
                    if (cachesChanged) {
                        writePropertyCaches();
                    }
                    writeACLEntries();
                    final EJBPropertyRowCache prc = cachesChanged ? getModifiedUnlocalizedPropertyCache() : null;
                    final TypeInfoMap infoMap = prc != null ? getTypeInfoMap() : null;
                    setEntityState(entityState.storeChanges(prc, infoMap));
                    setNeedsStoring(false);
                    success = true;
                } finally {
                    invalidate(success, AbstractCacheUnit.INVALIDATIONTYPE_MODIFIED);
                    audit.finish(success);
                }
            } else {
                setNeedsStoring(false);
            }
        }
    }

    private void checkOptimisticLockVersionIsValidForServiceLayer(final GenericItemEntityState entityState) {
        // optimistic locking enabled at all ?

        if (HJMPUtils.isOptimisticLockingEnabledForType(entityState.getTypePkString())) {
            final PK pkString = getPK();
            final Long versionFromServiceLayer = HJMPUtils.getVersionForPk(pkString);

            // was transaction initiated by service layer ?
            if (versionFromServiceLayer != null) {
                final long hjmpTSFromStartOfTransaction = entityState.hjmpTSFromStartOfTransaction;
                final long hjmpTS = entityState.hjmpTS;

                // did persistence layer version increase *without* service layer version being updated --> concurrent update detected !!!
                if (versionFromServiceLayer.longValue() < hjmpTSFromStartOfTransaction) {
                    throw new HJMPException(new HybrisOptimisticLockingFailureException("item pk " + pkString
                            + " was modified concurrently - expected version " + versionFromServiceLayer + " (from model) but got "
                            + hjmpTSFromStartOfTransaction + ".." + hjmpTS + ", entity state = " + entityState,
                            null));
                }
            }
        }
    }

    @Override
    protected int typeCode() {
        return getTypeCode();
    }

    @Override
    public boolean isBeforeCreate() {
        return beforeCreate;
    }

    @Override
    public PK ejbCreate(final PK pkBase, final ComposedTypeRemote type, final EJBPropertyContainer props) {
        try {
            ejbCreateNestedCounter++;
            if (ejbCreateNestedCounter == 1) {
                setEntityState(new GenericItemEntityState(getPersistencePool(), getDeployment()));
            } else {
                if (entityState == null) {
                    throw new HJMPException("no entity state in nested ejbCreate");
                }
            }
            beforeCreate = true;
            super.ejbCreate(pkBase, type, props);
            if (ejbCreateNestedCounter == 1) {
                boolean success = false;
                final AuditableOperation audit = aboutToExecute(Operation.create(getPkString(), getTypePkString()));

                try {
                    if (LOG || LOG_MODIFICATIONS) {
                        LOGGER.debug("EJB creating " + getPkString() + " in " + getID());
                    }
                    final EJBPropertyRowCache prc = getModifiedUnlocalizedPropertyCache();
                    final TypeInfoMap infoMap = prc != null ? getTypeInfoMap() : null;
                    getEntityState().createEntity(prc, infoMap);
                    beforeCreate = false;
                    writePropertyCaches();
                    writeACLEntries();
                    success = true;
                } finally {
                    addLocalRollbackInvalidation(true);
                    invalidate(success, AbstractCacheUnit.INVALIDATIONTYPE_CREATED);
                    beforeCreate = false;
                    audit.finish(success);
                }
            } else {
                if (LOG) {
                    LOGGER.debug("nested ejbCreate in " + getID());
                }
            }
            return getPkString();
        } finally {
            ejbCreateNestedCounter--;
        }
    }

    @Override
    public GenericItemRemote create(final PK pkBase, final ComposedTypeRemote type, final EJBPropertyContainer props) {
        throw new RuntimeException("never called!");
    }


    @Override
    public void ejbPostCreate(final PK param0, final de.hybris.platform.persistence.type.ComposedTypeRemote param1,
                              final de.hybris.platform.persistence.property.EJBPropertyContainer param2) {
        super.ejbPostCreate(param0, param1, param2);
        if (needsStoring()) {
            final EJBPropertyRowCache prc = getModifiedUnlocalizedPropertyCache();
            final TypeInfoMap infoMap = prc != null ? getTypeInfoMap() : null;

            final GenericItemEntityState entityState = getEntityState();
            checkOptimisticLockVersionIsValidForServiceLayer(entityState);
            setEntityState(entityState.storeChanges(prc, infoMap));
        }
    }

    @Override
    public void ejbRemove() {
        loadData();

        boolean success = false;
        final AuditableOperation audit = aboutToExecute(Operation.delete(getPkString(), getTypePkString()));

        try {
            super.ejbRemove();
            if (LOG || LOG_MODIFICATIONS) {
                LOGGER.debug("EJB removing " + getPkString() + " in " + getID());
            }
            if (!Config.getBoolean("props.removal.optimisation", true)) {
                removePropertyData();
            }
            removeACLEntries();
            setEntityState(getEntityState().removeEntity());
            success = true;
        } finally {
            invalidate(success, AbstractCacheUnit.INVALIDATIONTYPE_REMOVED);
            audit.finish(success);
        }
        clearFields();
    }

    @Override
    public void ejbHomeLoadItemData(final ResultSet resultSet) {
        final PK pk = handleResultRow(getPersistencePool(), resultSet, getDeployment());
        if (LOG && LOGGER.isDebugEnabled()) {
            LOGGER.debug("loaded item " + pk);
        }
    }

    private static Collection handleResult(final PersistencePool pool, final ResultSet resultSet, final ItemDeployment depl) {
        try {
            if (resultSet.next()) {
                final Collection result = new ArrayList();
                do {
                    result.add(handleResultRow(pool, resultSet, depl));
                }
                while (resultSet.next());
                return result;
            } else {
                return java.util.Collections.EMPTY_LIST;
            }
        } catch (final SQLException e) {
            throw new HJMPException(e);
        }
    }


    private static PK handleResultRow(final PersistencePool pool, final ResultSet resultSet, final ItemDeployment depl) {
        try {
            final PK pk = (PK) ((ValueReader) PK_RW[0]).getValue(resultSet, "PK");
            new GenericItemEntityStateCacheUnit(pool.getCache(), pk, depl)
                    .hintEntityState(new GenericItemEntityState(pool, resultSet, pk, depl));
            return pk;
        } catch (final SQLException e) {
            throw new HJMPException(e);
        }
    }

    // ----- Attributes -----

    @Override
    public PK getPkString() {
        final GenericItemEntityState entityState = getEntityState();
        final PK result = getEntityState().getPkString();
        if (LOG_ATTRIBUTEACCESS) {
            LOGGER.debug("getting PkString for " + entityContext.getPK() + " in GenericItem eid=" + getID() + " sid="
                    + entityState.getID() + ": " + result);
        }
        return result;
    }

    @Override
    public void setPkString(final PK newvalue) {
        final GenericItemEntityState oldEntityState = getEntityState();
        GenericItemEntityState newEntityState = null;
        if (CHECK_VALUE_DIFFERENCES) {
            final PK oldvalue = oldEntityState.getPkString();
            if (oldvalue != newvalue && (oldvalue == null || !oldvalue.equals(newvalue))) {
                newEntityState = oldEntityState.setPkString(newvalue);
                setEntityState(newEntityState);
            }
        } else {
            newEntityState = oldEntityState.setPkString(newvalue);
            setEntityState(newEntityState);
        }
        if (LOG_ATTRIBUTEACCESS) {
            String sidString;
            if (oldEntityState == newEntityState) {
                sidString = "sid=" + oldEntityState.getID();
            } else {
                sidString = "oldsid=" + oldEntityState.getID();
                if (newEntityState != null) {
                    sidString += " newsid=" + newEntityState.getID();
                }
            }
            LOGGER.debug(
                    "setting PkString for " + entityContext.getPK() + " in GenericItem eid=" + getID() + " " + sidString + ": "
                            + newvalue);
        }
    }

    @Override
    public Date getCreationTimestampInternal() {
        final GenericItemEntityState entityState = getEntityState();
        final Date result = getEntityState().getCreationTimestampInternal();
        if (LOG_ATTRIBUTEACCESS) {
            LOGGER.debug("getting CreationTimestampInternal for " + entityContext.getPK() + " in GenericItem eid=" + getID()
                    + " sid=" + entityState.getID() + ": " + result);
        }
        return result;
    }

    @Override
    public void setCreationTimestampInternal(final Date newvalue) {
        final GenericItemEntityState oldEntityState = getEntityState();
        GenericItemEntityState newEntityState = null;

        //similar to HOR-1573, no need to compare the time for new entity
        newEntityState = oldEntityState.setCreationTimestampInternal(newvalue);
        setEntityState(newEntityState);

        if (LOG_ATTRIBUTEACCESS) {
            final String sidString;
            if (oldEntityState == newEntityState) {
                sidString = "sid=" + oldEntityState.getID();
            } else {
                sidString = "oldsid=" + oldEntityState.getID() + " newsid=" + newEntityState.getID();
            }
            LOGGER.debug("setting CreationTimestampInternal for " + entityContext.getPK() + " in GenericItem eid=" + getID() + " "
                    + sidString + ": " + newvalue);
        }
    }

    @Override
    public Date getModifiedTimestampInternal() {
        final GenericItemEntityState entityState = getEntityState();
        final Date result = getEntityState().getModifiedTimestampInternal();
        if (LOG_ATTRIBUTEACCESS) {
            LOGGER.debug("getting ModifiedTimestampInternal for " + entityContext.getPK() + " in GenericItem eid=" + getID()
                    + " sid=" + entityState.getID() + ": " + result);
        }
        return result;
    }

    @Override
    public void setModifiedTimestampInternal(final Date newvalue) {
        final GenericItemEntityState oldEntityState = getEntityState();
        GenericItemEntityState newEntityState = null;

        //HOR-1573
        newEntityState = oldEntityState.setModifiedTimestampInternal(newvalue);
        setEntityState(newEntityState);

        if (LOG_ATTRIBUTEACCESS) {
            final String sidString;
            if (oldEntityState == newEntityState) {
                sidString = "sid=" + oldEntityState.getID();
            } else {
                sidString = "oldsid=" + oldEntityState.getID() + " newsid=" + newEntityState.getID();
            }
            LOGGER.debug("setting ModifiedTimestampInternal for " + entityContext.getPK() + " in GenericItem eid=" + getID() + " "
                    + sidString + ": " + newvalue);
        }
    }

    @Override
    public long getACLTimestampInternal() {
        final GenericItemEntityState entityState = getEntityState();
        final long result = getEntityState().getACLTimestampInternal();
        if (LOG_ATTRIBUTEACCESS) {
            LOGGER.debug("getting ACLTimestampInternal for " + entityContext.getPK() + " in GenericItem eid=" + getID() + " sid="
                    + entityState.getID() + ": " + result);
        }
        return result;
    }

    @Override
    public void setACLTimestampInternal(final long newvalue) {
        final GenericItemEntityState oldEntityState = getEntityState();
        GenericItemEntityState newEntityState = null;
        if (CHECK_VALUE_DIFFERENCES) {
            final long oldvalue = oldEntityState.getACLTimestampInternal();
            if (oldvalue != newvalue) {
                newEntityState = oldEntityState.setACLTimestampInternal(newvalue);
                setEntityState(newEntityState);
            }
        } else {
            newEntityState = oldEntityState.setACLTimestampInternal(newvalue);
            setEntityState(newEntityState);
        }
        if (LOG_ATTRIBUTEACCESS) {
            String sidString;
            if (oldEntityState == newEntityState) {
                sidString = "sid=" + oldEntityState.getID();
            } else {
                sidString = "oldsid=" + oldEntityState.getID();
                if (newEntityState != null) {
                    sidString += " newsid=" + newEntityState.getID();
                }
            }
            LOGGER.debug("setting ACLTimestampInternal for " + entityContext.getPK() + " in GenericItem eid=" + getID() + " "
                    + sidString + ": " + newvalue);
        }
    }

    @Override
    public PK getTypePkString() {
        final GenericItemEntityState entityState = getEntityState();
        final PK result = getEntityState().getTypePkString();
        if (LOG_ATTRIBUTEACCESS) {
            LOGGER.debug("getting TypePkString for " + entityContext.getPK() + " in GenericItem eid=" + getID() + " sid="
                    + entityState.getID() + ": " + result);
        }
        return result;
    }

    @Override
    public void setTypePkString(final PK newvalue) {
        final GenericItemEntityState oldEntityState = getEntityState();
        GenericItemEntityState newEntityState = null;
        if (CHECK_VALUE_DIFFERENCES) {
            final PK oldvalue = oldEntityState.getTypePkString();
            if (oldvalue != newvalue && (oldvalue == null || !oldvalue.equals(newvalue))) {
                newEntityState = oldEntityState.setTypePkString(newvalue);
                setEntityState(newEntityState);
            }
        } else {
            newEntityState = oldEntityState.setTypePkString(newvalue);
            setEntityState(newEntityState);
        }
        if (LOG_ATTRIBUTEACCESS) {
            String sidString;
            if (oldEntityState == newEntityState) {
                sidString = "sid=" + oldEntityState.getID();
            } else {
                sidString = "oldsid=" + oldEntityState.getID();
                if (newEntityState != null) {
                    sidString += " newsid=" + newEntityState.getID();
                }
            }
            LOGGER.debug("setting TypePkString for " + entityContext.getPK() + " in GenericItem eid=" + getID() + " " + sidString
                    + ": " + newvalue);
        }
    }

    @Override
    public PK getOwnerPkString() {
        final GenericItemEntityState entityState = getEntityState();
        final PK result = getEntityState().getOwnerPkString();
        if (LOG_ATTRIBUTEACCESS) {
            LOGGER.debug("getting OwnerPkString for " + entityContext.getPK() + " in GenericItem eid=" + getID() + " sid="
                    + entityState.getID() + ": " + result);
        }
        return result;
    }

    @Override
    public void setOwnerPkString(final PK newvalue) {
        final GenericItemEntityState oldEntityState = getEntityState();
        GenericItemEntityState newEntityState = null;
        if (CHECK_VALUE_DIFFERENCES) {
            final PK oldvalue = oldEntityState.getOwnerPkString();
            if (oldvalue != newvalue && (oldvalue == null || !oldvalue.equals(newvalue))) {
                newEntityState = oldEntityState.setOwnerPkString(newvalue);
                setEntityState(newEntityState);
            }
        } else {
            newEntityState = oldEntityState.setOwnerPkString(newvalue);
            setEntityState(newEntityState);
        }
        if (LOG_ATTRIBUTEACCESS) {
            String sidString;
            if (oldEntityState == newEntityState) {
                sidString = "sid=" + oldEntityState.getID();
            } else {
                sidString = "oldsid=" + oldEntityState.getID();
                if (newEntityState != null) {
                    sidString += " newsid=" + newEntityState.getID();
                }
            }
            LOGGER.debug("setting OwnerPkString for " + entityContext.getPK() + " in GenericItem eid=" + getID() + " " + sidString
                    + ": " + newvalue);
        }
    }

    @Override
    public long getPropertyTimestampInternal() {
        final GenericItemEntityState entityState = getEntityState();
        final long result = getEntityState().getPropertyTimestampInternal();
        if (LOG_ATTRIBUTEACCESS) {
            LOGGER.debug("getting PropertyTimestampInternal for " + entityContext.getPK() + " in GenericItem eid=" + getID()
                    + " sid=" + entityState.getID() + ": " + result);
        }
        return result;
    }

    @Override
    public void setPropertyTimestampInternal(final long newvalue) {
        final GenericItemEntityState oldEntityState = getEntityState();
        GenericItemEntityState newEntityState = null;
        if (CHECK_VALUE_DIFFERENCES) {
            final long oldvalue = oldEntityState.getPropertyTimestampInternal();
            if (oldvalue != newvalue) {
                newEntityState = oldEntityState.setPropertyTimestampInternal(newvalue);
                setEntityState(newEntityState);
            }
        } else {
            newEntityState = oldEntityState.setPropertyTimestampInternal(newvalue);
            setEntityState(newEntityState);
        }
        if (LOG_ATTRIBUTEACCESS) {
            String sidString;
            if (oldEntityState == newEntityState) {
                sidString = "sid=" + oldEntityState.getID();
            } else {
                sidString = "oldsid=" + oldEntityState.getID();
                if (newEntityState != null) {
                    sidString += " newsid=" + newEntityState.getID();
                }
            }
            LOGGER.debug("setting PropertyTimestampInternal for " + entityContext.getPK() + " in GenericItem eid=" + getID() + " "
                    + sidString + ": " + newvalue);
        }
    }

    @Override
    public PK ejbFindByPrimaryKey(final PK pkValue) throws YObjectNotFoundException {
        if (LOG) {
            LOGGER.debug("GenericItem ejbFindByPrimaryKey " + pkValue);
        }
        final AbstractEntityState cacheEntry = withRRDS(() -> GenericItemEntityState
                .getInstance(getPersistencePool().getCache(), pkValue, getDeployment()).getEntityState());
        if (cacheEntry == null) {
            if (LOG) {
                LOGGER.debug("'" + pkValue + "'  not found");
            }
            throw new YObjectNotFoundException("'" + pkValue + "'  not found");
        } else {
            if (LOG) {
                LOGGER.debug("  found entry");
            }
            return cacheEntry.getPK();
        }
    }

    /*
     * azuresqlenhancements - START
     */
    private <T> T withRRDS(final Supplier<T> logic) {
        // read-replica enabled ?
        if (!isGenericCacheReadReplicaEnabled()) {
            return logic.get();
        }
        final Tenant tenant = getPersistencePool().getCache().getTenant();
        final boolean forceMasterIsSet = tenant.isForceMaster();
        try {
            if (forceMasterIsSet) {
                ((AbstractTenant) tenant).cancelForceMasterMode();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Had to reset 'force master' mode!");
                }
            }
            final String dsId = tenant.getConfig().getString(ReadOnlyConditionsHelper.PARAM_FS_READ_ONLY_DATASOURCE, "readonly");
            tenant.activateSlaveDataSource(dsId);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Activated slave datasource '" + dsId + "' for HAC queries.");
            }

            return logic.get();
        } finally {
            tenant.deactivateAlternativeDataSource();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("De-activated slave datasource.");
            }
            if (forceMasterIsSet) {
                tenant.forceMasterDataSource();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Restored 'force master' mode!");
                }
            }
        }
    }

    /**
     * this method checks whether all below conditions are fulfilled:<br/>
     * <ul>
     *     <li>there is <strong>no</strong> {@link Transaction} currently active ;</li>
     * </ul>
     *
     * @return {@code true} if all conditions mentioned in the description are fulfilled, otherwise {@code false}
     */
    private boolean isGenericCacheReadReplicaEnabled() {
        if (Transaction.current().isRunning()) {
            LOGGER.debug("transaction is active - can't use read-only datasource");
            return false;
        }
        if (Config.getBoolean("azureoptimizationsreadonly.read-replica.entity.enabled", false)) {

            final JaloSession currentSession = JaloSession.getCurrentSession(getPersistencePool().getCache().getTenant());
            if (currentSession == null) {
                return false;
            }
            return readOnlyDataSourceEnabledInSessionContext(currentSession.getSessionContext()).or(() -> checkTables()).orElse(false);
        }

        return false;
    }

    protected Optional<Boolean> readOnlyDataSourceEnabledInSessionContext(final SessionContext ctx) {
        final Object attribute = Objects.requireNonNull(ctx).getAttribute(ReadOnlyConditionsHelper.CTX_ENABLE_FS_ON_READ_REPLICA);
        if (attribute == null) {
            return Optional.empty();
        }

        final boolean ret;
        if (attribute instanceof Boolean) {
            ret = (Boolean) attribute;
        } else if (attribute instanceof String) {
            final String s = ((String) attribute).trim();
            if (Boolean.TRUE.toString().equalsIgnoreCase(s)) {
                ret = true;
            } else if (Boolean.FALSE.toString().equalsIgnoreCase(s)) {
                ret = false;
            } else {
                return Optional.empty();
            }
        } else {
            ret = false;
        }

        return Optional.of(ret);
    }


    /**
     * this method checks whether all below conditions are fulfilled:<br/>
     *
     * @return {@code true} if all conditions mentioned in the description are fulfilled, otherwise {@code false}
     */
    private Optional<Boolean> checkTables() {
        final String tables = Config.getString("azureoptimizationsreadonly.read-replica.entity.tables.list", "");

        if (StringUtils.isEmpty(tables)) {
            return Optional.of(false);
        }

        final Set<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        final String[] tablesArray = tables.split(",");
        result.addAll(Arrays.stream(tablesArray).collect(Collectors.toSet()));

        return Optional.of(result.stream().anyMatch(e -> StringUtils.equalsIgnoreCase(e, getTable())));
    }

    /*
     * azuresqlenhancements - END
     */

    public GenericItemRemote findByPrimaryKey(@SuppressWarnings("unused") final PK pk)
            throws YObjectNotFoundException, YFinderException {
        throw new HJMPException("illegal call to findByPrimaryKey!");
    }

    @Override
    public Collection findChangedAfter(final Date param0) throws YFinderException {
        throw new HJMPException("findChangedAfter() not supported in generic deployments");
    }

    public java.util.Collection ejbFindAll() throws YFinderException {
        throw new HJMPException("findAll() no supported in generic deployments");
    }

    @Override
    public Collection findAll() throws YFinderException {
        throw new HJMPException("findAll() no supported in generic deployments");
    }

    private static abstract class GenericBMPFinderResult extends FinderResult {
        GenericBMPFinderResult(final PersistencePool pool, final ItemDeployment depl, final String finderName,
                               final Object[] parameters) {
            super(pool, depl, PlatformStringUtils.valueOf(depl.getTypeCode()), finderName, parameters);
        }
    }

    public static boolean checkTypeCodes(final Collection<PK> pks, final int typeCode) {
        if (pks != null) {
            for (final PK pk : pks) {
                if (pk.getTypeCode() != typeCode) {
                    return false;
                }
            }
        }
        return true;
    }

    private static final class FindByPKListFinderResult extends GenericBMPFinderResult {
        private final Collection<PK> _pkList;

        FindByPKListFinderResult(final PersistencePool pool, final ItemDeployment depl, final Collection<PK> pkList) {
            super(pool, depl, "ejbFindByPKList", new Object[]
                    {pkList});
            Preconditions.checkArgument(checkTypeCodes(pkList, depl.getTypeCode()));
            this._pkList = pkList;
        }

        public Object getFinderResult() throws SQLException, YFinderException {
            try {
                return super.get();
            } catch (final SQLException e) {
                throw e;
            } catch (final YFinderException e) {
                throw e;
            } catch (final RuntimeException e) {
                throw e;
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Object compute() throws SQLException, YFinderException {
            final Collection<ItemRemote> ret = new ArrayList<ItemRemote>(_pkList.size());
            Connection connection = null;
            Statement statement = null;
            ResultSet resultSet = null;

            try {
                connection = pool.getDataSource().getConnection();

                int pageSize = pool.getDataSource().getMaxPreparedParameterCount();
                if (pageSize == -1) {
                    pageSize = _pkList.size();
                }
                int offset = 0;
                final List<PK> pks = new ArrayList<PK>(_pkList);

                while (offset < pks.size()) {
                    final int currentPageEnd = Math.min(pks.size(), offset + pageSize);

                    final StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append("SELECT * FROM ").append(getTable()).append(" WHERE PK IN (");
                    // insert ?'s
                    for (int i = 0; i < currentPageEnd - offset; i++) {
                        stringBuilder.append(i > 0 ? "," : "").append("?");
                    }
                    stringBuilder.append(")");
                    statement = connection.prepareStatement(stringBuilder.toString());
                    // now bind parameters
                    int paramPos = 1;
                    for (int i = offset; i < currentPageEnd; i++) {
                        ((ValueWriter) PK_RW[WRITER]).setValue((PreparedStatement) statement, paramPos++, pks.get(i));
                    }
                    resultSet = ((PreparedStatement) statement).executeQuery();

                    ret.addAll(handleResult(pool, resultSet, getDeployment()));

                    // close stmt and rs to next turn
                    HJMPUtils.tryToClose(statement, resultSet);

                    statement = null;
                    resultSet = null;

                    // jump to next page for next turn
                    offset += pageSize;
                }

                return ret;
            } finally {
                HJMPUtils.tryToClose(connection, statement, resultSet);
            }
        }

    }

    private static final class FindByType1FinderResult extends GenericBMPFinderResult {
        private final PK typePK;

        FindByType1FinderResult(final PersistencePool pool, final ItemDeployment depl, final PK typePK) {
            super(pool, depl, "ejbFindByType", new Object[]
                    {typePK});
            this.typePK = typePK;
        }

        public Object getFinderResult() throws SQLException, YFinderException {
            try {
                return super.get();
            } catch (final SQLException e) {
                throw e;
            } catch (final YFinderException e) {
                throw e;
            } catch (final RuntimeException e) {
                throw e;
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Object compute() throws SQLException, YFinderException {
            if (LOG && LOGGER.isDebugEnabled()) {
                LOGGER.debug("computing FindByType1FinderResult");
            }
            Connection connection = null;
            Statement statement = null;
            ResultSet resultSet = null;
            try {
                connection = pool.getDataSource().getConnection();
                statement = connection
                        .prepareStatement("SELECT * FROM " + getTable() + " WHERE " + getColumn("typePkString") + " = ?");
                ((ValueWriter) PK_RW[WRITER]).setValue((PreparedStatement) statement, 1, typePK);
                resultSet = ((PreparedStatement) statement).executeQuery();
                final Collection result = handleResult(pool, resultSet, getDeployment());
                if (LOG && LOGGER.isDebugEnabled()) {
                    LOGGER.debug("    found " + result);
                }
                return result;
            } finally {
                HJMPUtils.tryToClose(connection, statement, resultSet);
            }
        }
    }

    public Collection ejbFindByPKList(final Collection<PK> pkList) throws YFinderException {
        try {
            return (Collection) new FindByPKListFinderResult(getPersistencePool(), getDeployment(), pkList).getFinderResult();
        } catch (final SQLException e) {
            throw new HJMPFinderException(e);
        }
    }

    public java.util.Collection ejbFindByType(final PK typePK) throws YFinderException {
        try {
            if (LOG) {
                LOGGER.debug("GenericBMPBean ejbFindByType");
            }
            if (LOG) {
                LOGGER.debug("  0->" + typePK);
            }
            final Object resultObject = new FindByType1FinderResult(getPersistencePool(), getDeployment(),
                    typePK).getFinderResult();
            if (LOG) {
                LOGGER.debug("  result=" + resultObject);
            }
            return (java.util.Collection) resultObject;
        } catch (final SQLException e) {
            throw new HJMPFinderException(e);
        }
    }

    @Override
    public Collection findByType(final PK typePK) throws YFinderException {
        return (Collection) EJBTools.convertEntityFinderResult(ejbFindByType(typePK),
                getEntityContext().getPersistencePool()
                        .getTenant()
                        .getSystemEJB());
    }

    @Override
    public Collection findByPKList(final Collection pks) throws YFinderException {
        return (Collection) EJBTools.convertEntityFinderResult(ejbFindByPKList(pks),
                getEntityContext().getPersistencePool()
                        .getTenant()
                        .getSystemEJB());
    }

    @Override
    protected String getItemTableNameImpl() {
        return getTable();
    }

    @Override
    public String getOwnJNDIName() {
        return getDeployment().getName();
    }

    @Override
    public String getPropertyTableNameImpl() {
        return getDumptable();
    }

    @Override
    protected Object getCachedValueForModification(final ItemCacheKey key) {
        setEntityState(getEntityState().getModifiedVersion());
        return super.getCachedValueForModification(key);
    }

    @Override
    protected Map getCacheKeyMap() {
        return getEntityState().getCacheKeyMap();
    }

    final static class GenericItemEntityState extends AbstractEntityState {
        static GenericItemEntityStateCacheUnit getInstance(final Cache cache, final PK pk, final ItemDeployment depl) {
            return new GenericItemEntityStateCacheUnit(cache, pk, depl);
        }

        // hjmp timestamp
        // We're keeping the original version for optimistic locking inside *nested* transactions which lead
        // to *multiple* writes and therefore *multiple* version increments. In that case we want to know which
        // version we initially started when we read the item from database!
        private long hjmpTSFromStartOfTransaction = 0;
        private long hjmpTS = 0;
        private final BitSet changes;
        //
        private final boolean isTransactionBound;
        private PK pkString;
        private Date creationTimestampInternal;
        private Date modifiedTimestampInternal;
        private long aCLTimestampInternal;
        private PK typePkString;
        private PK ownerPkString;
        private long propertyTimestampInternal;
        private Map cacheKeyMap;

        /**
         * create a new instance, initially not backed by a database entity
         */
        GenericItemEntityState(final PersistencePool pool, final ItemDeployment depl) {
            super(pool, depl);
            if (LOG) {
                LOGGER.debug("creating new tx-bound entity state for new GenericItem");
            }
            isTransactionBound = true;
            changes = new BitSet(6);
        }

        /**
         * create a new instance by reading the entity with the given pk from the database
         */
        GenericItemEntityState(final PersistencePool pool, final PK pk, final Connection connection, final ItemDeployment depl) {
            super(pool, depl);

            PreparedStatement statement = null;
            ResultSet resultSet = null;
            try {
                Preconditions.checkArgument(checkPK(pk));
                this.pkString = pk;
                statement = connection.prepareStatement("SELECT * FROM " + getTable() + " WHERE PK=?");
                ((ValueWriter) PK_RW[WRITER]).setValue(statement, 1, pk);
                resultSet = statement.executeQuery();
                if (!resultSet.next()) {
                    resultSet = HJMPUtils.retryMissingPKLookup(resultSet, statement, getPool().getTenant().getConfig());
                    if (resultSet == null) {
                        throw new YNoSuchEntityException("found 0 lines for PK " + pk, pk);
                    }
                }
                setStateFromResultSet(resultSet);
                //Log.debug("hjmp", "loaded "+pk+" version "+hjmpTS+"..." );
                if (resultSet.next()) {
                    throw new HJMPException("found multiple lines for PK " + pk);
                }
                if (!pk.equals(pkString)) {
                    throw new HJMPException("found PK " + pkString + ", expected " + pk);
                }
                isTransactionBound = false;
                changes = null;
            } catch (final SQLException e) {
                throw new HJMPException(e);
            } finally {
                HJMPUtils.tryToClose(statement, resultSet);
            }
        }

        /**
         * create a new instance with data from the current line of the given QueryResult
         */
        GenericItemEntityState(final PersistencePool pool, final ResultSet resultSet, final PK pk, final ItemDeployment depl) {
            super(pool, depl);
            isTransactionBound = false;

            Preconditions.checkArgument(checkPK(pk));
            this.pkString = pk;

            setStateFromResultSet(resultSet);
            changes = null;

            //Log.debug("hjmp", "loaded "+pkString+" version "+hjmpTS+"..." );
        }

        GenericItemEntityState(final GenericItemEntityState original, final ItemDeployment depl) {
            super(original.getPool(), depl);
            if (LOG) {
                LOGGER.debug("creating tx-bound entity state for " + original.getPK());
            }
            changes = new BitSet(6);
            hjmpTS = original.hjmpTS;
            hjmpTSFromStartOfTransaction = original.hjmpTSFromStartOfTransaction;
            pkString = original.getPkString();
            creationTimestampInternal = original.getCreationTimestampInternal();
            modifiedTimestampInternal = original.getModifiedTimestampInternal();
            aCLTimestampInternal = original.getACLTimestampInternal();
            typePkString = original.getTypePkString();
            ownerPkString = original.getOwnerPkString();
            propertyTimestampInternal = original.getPropertyTimestampInternal();
            if (original.cacheKeyMap != null) {
                cacheKeyMap = new HashMap();
                final Iterator iter = original.cacheKeyMap.entrySet().iterator();
                while (iter.hasNext()) {
                    final Map.Entry next = (Map.Entry) iter.next();
                    final Object cacheQualifier = next.getKey();
                    final ItemCacheKey itemCacheKey = ((ItemCacheKey) next.getValue()).getCopy();
                    cacheKeyMap.put(cacheQualifier, itemCacheKey);
                }
            }
            isTransactionBound = true;
        }

        @Override
        public String getFullBeanName() {
            return "de.hybris.platform.persistence.GenericItem";
        }

        private void setStateFromResultSet(final ResultSet resultSet) {
            try {
                hjmpTS = resultSet.getLong("hjmpTS");
                hjmpTSFromStartOfTransaction = hjmpTS;
                creationTimestampInternal = (java.util.Date) ((ValueReader) DATE_RW[READER]).getValue(resultSet,
                        getColumn(
                                "creationTimestampInternal"));
                modifiedTimestampInternal = (java.util.Date) ((ValueReader) DATE_RW[READER]).getValue(resultSet,
                        getColumn(
                                "modifiedTimestampInternal"));
                aCLTimestampInternal = ((ValueReader) LONG_RW[READER]).getLong(resultSet, getColumn("aCLTimestampInternal"));
                typePkString = (PK) ((ValueReader) PK_RW[READER]).getValue(resultSet, getColumn("typePkString"));
                ownerPkString = (PK) ((ValueReader) PK_RW[READER]).getValue(resultSet, getColumn("ownerPkString"));
                propertyTimestampInternal = ((ValueReader) LONG_RW[READER]).getLong(resultSet,
                        getColumn("propertyTimestampInternal"));
                //--- UP-Table included ---------------------------------------------------------

                final TypeInfoMap tim = (typePkString != null
                        && (!pkString.equals(typePkString) || Registry.getPersistenceManager().cachesInfoFor(typePkString)))
                        ? Registry.getPersistenceManager().getPersistenceInfo(typePkString)
                        : null;
                if (tim != null && tim.tablesInitialized() && tim.hasInfos(false)) {
                    final EJBPropertyRowCache prc = PropertyJDBC.readPropertyRow(resultSet, pkString, typePkString, null,
                            propertyTimestampInternal, tim);
                    getCacheKeyMap().put(ItemPropertyCacheKey.QUALI, new ItemPropertyCacheKey(prc, pkString));
                }
                //--- UP-Table included ---------------------------------------------------------
            } catch (final SQLException e) {
                throw new HJMPException(e);
            }
        }

        private void wroteChanges() {
            final int size = changes.length();
            for (int i = 0; i < size; i++) {
                changes.clear(i);
            }
        }

        private boolean hasChangedFields() {
            return changes != null && changes.length() > 0;
        }

        private void markChanged(final int pos) {
            changes.set(pos);
        }

        private boolean wasChanged(final int pos) {
            return changes.get(pos);
        }

        @Override
        public PK getPK() {
            return getPkString();
        }

        public GenericItemEntityState getModifiedVersion() {
            if (isTransactionBound) {
                return this;
            } else {
                final GenericItemEntityState newState = new GenericItemEntityState(this, getDeployment());
                if (LOG_MODIFICATIONS) {
                    LOGGER.debug("created modified version of " + toString());
                }
                return newState;
            }
        }

        public PK getPkString() {
            return pkString;
        }

        public GenericItemEntityState setPkString(final PK newValue) {
            Preconditions.checkArgument(checkPK(newValue));
            final GenericItemEntityState newState = getModifiedVersion();
            newState.pkString = newValue;
            newState.markChanged(0);
            return newState;
        }

        public java.util.Date getCreationTimestampInternal() {
            return creationTimestampInternal;
        }

        public GenericItemEntityState setCreationTimestampInternal(final java.util.Date newValue) {
            final GenericItemEntityState newState = getModifiedVersion();
            newState.creationTimestampInternal = newValue;
            newState.markChanged(1);
            return newState;
        }

        public java.util.Date getModifiedTimestampInternal() {
            return modifiedTimestampInternal;
        }

        public GenericItemEntityState setModifiedTimestampInternal(final java.util.Date newValue) {
            final GenericItemEntityState newState = getModifiedVersion();
            newState.modifiedTimestampInternal = newValue;
            newState.markChanged(2);
            return newState;
        }

        public long getACLTimestampInternal() {
            return aCLTimestampInternal;
        }

        public GenericItemEntityState setACLTimestampInternal(final long newValue) {
            final GenericItemEntityState newState = getModifiedVersion();
            newState.aCLTimestampInternal = newValue;
            newState.markChanged(3);
            return newState;
        }

        public PK getTypePkString() {
            return typePkString;
        }

        public GenericItemEntityState setTypePkString(final PK newValue) {
            final GenericItemEntityState newState = getModifiedVersion();
            newState.typePkString = newValue;
            newState.markChanged(4);
            return newState;
        }

        public PK getOwnerPkString() {
            return ownerPkString;
        }

        public GenericItemEntityState setOwnerPkString(final PK newValue) {
            final GenericItemEntityState newState = getModifiedVersion();
            newState.ownerPkString = newValue;
            newState.markChanged(5);
            return newState;
        }

        public long getPropertyTimestampInternal() {
            return propertyTimestampInternal;
        }

        public GenericItemEntityState setPropertyTimestampInternal(final long newValue) {
            final GenericItemEntityState newState = getModifiedVersion();
            newState.propertyTimestampInternal = newValue;
            newState.markChanged(6);
            return newState;
        }

        GenericItemEntityState storeChanges(final EJBPropertyRowCache prc, final TypeInfoMap typeInfoMap) {
            Connection connection = null;
            PreparedStatement statement = null;
            final ResultSet resultSet = null;

            if (!isTransactionBound) {
                LOGGER.error("ejbStore with !isTransactionBound!!", new Exception());
                return this;
            }
            try {
                // make sure we're using master data source before storing
                getPool().getTenant().forceMasterDataSource();

                // now we should be getting the master data source
                Preconditions.checkArgument(!getPool().getTenant().isSlaveDataSource());

                connection = getPool().getDataSource().getConnection();

                final boolean isSaveFromSL = HJMPUtils.isFromServiceLayer(pkString);

                // CHECK for changes first
                final List[] data = prc != null ? PropertyJDBC.getChangeData(connection, typeInfoMap, prc, false) : null;
                if (hasChangedFields() || (data != null && data.length > 0) || isSaveFromSL) {
                    final boolean optimisticLockingEnabled = HJMPUtils.isOptimisticLockingEnabledForType(getTypePkString());
                    final boolean versionTrackedFromSL = HJMPUtils.isFromServiceLayer(pkString);
                    this.hjmpTS = hjmpTS + 1;

                    final StringBuilder stringBuilder = new StringBuilder("UPDATE " + getTable() + " SET hjmpTS = ? ");
                    if (wasChanged(1)) {
                        stringBuilder.append(",").append(getColumn("creationTimestampInternal")).append("=?");
                    }
                    if (wasChanged(2)) {
                        stringBuilder.append(",").append(getColumn("modifiedTimestampInternal")).append("=?");
                    }
                    if (wasChanged(3)) {
                        stringBuilder.append(",").append(getColumn("aCLTimestampInternal")).append("=?");
                    }
                    if (wasChanged(4)) {
                        stringBuilder.append(",").append(getColumn("typePkString")).append("=?");
                    }
                    if (wasChanged(5)) {
                        stringBuilder.append(",").append(getColumn("ownerPkString")).append("=?");
                    }
                    if (wasChanged(6)) {
                        stringBuilder.append(",").append(getColumn("propertyTimestampInternal")).append("=?");
                    }
                    if (data != null) {
                        for (int i = 0; i < data[0].size(); i++) {
                            stringBuilder.append(",").append((String) data[0].get(i)).append("=?");
                        }
                    }
                    stringBuilder.append(" WHERE PK = ? ");
                    if (optimisticLockingEnabled) {
                        stringBuilder.append("AND hjmpTS = ? ");
                    }

                    stringBuilder.append("AND (sealed IS NULL OR sealed=0)");
                    statement = connection.prepareStatement(stringBuilder.toString());
                    int writeCount = 1;
                    // hjmpTS = ? <-- newTS
                    ((ValueWriter) LONG_RW[WRITER]).setPrimitive(statement, writeCount++, hjmpTS);
                    if (wasChanged(1)) {
                        ((ValueWriter) DATE_RW[WRITER]).setValue(statement, writeCount++, creationTimestampInternal);
                    }
                    if (wasChanged(2)) {
                        ((ValueWriter) DATE_RW[WRITER]).setValue(statement, writeCount++, modifiedTimestampInternal);
                    }
                    if (wasChanged(3)) {
                        ((ValueWriter) LONG_RW[WRITER]).setPrimitive(statement, writeCount++, aCLTimestampInternal);
                    }
                    if (wasChanged(4)) {
                        ((ValueWriter) PK_RW[WRITER]).setValue(statement, writeCount++, typePkString);
                    }
                    if (wasChanged(5)) {
                        ((ValueWriter) PK_RW[WRITER]).setValue(statement, writeCount++, ownerPkString);
                    }
                    if (wasChanged(6)) {
                        ((ValueWriter) LONG_RW[WRITER]).setPrimitive(statement, writeCount++, propertyTimestampInternal);
                    }
                    if (data != null) {
                        for (int i = 0; i < data[0].size(); i++) {
                            //Log.debug( "bugfix", "setting unloc property "+data[0].get(i)+" to "+data[1].get(i) );
                            ((ValueWriter) data[2].get(i)).setValue(statement, writeCount++, data[1].get(i));
                        }
                    }

                    ((ValueWriter) PK_RW[WRITER]).setValue(statement, writeCount++, pkString);

                    if (optimisticLockingEnabled) {
                        statement.setLong(writeCount++, hjmpTS - 1);
                    }

                    final int modifiedLines = statement.executeUpdate();

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                Thread.currentThread() + " updating " + pkString + " version " + (hjmpTS - 1) + " to " + hjmpTS
                                        + " entity state = " + this + " modified lines = " + modifiedLines);
                    }
                    checkResult(connection, hjmpTS - 1, modifiedLines, optimisticLockingEnabled);
                    if (versionTrackedFromSL) {
                        HJMPUtils.updateVersionForPk(pkString, Long.valueOf(hjmpTS));
                    }
                }

                if (prc != null) {
                    prc.wroteChanges(true);
                }
                wroteChanges();
                return this;
            } catch (final SQLException e) {
                throw new HJMPException(getPool().getDataSource().translateToDataAccessException(e), getDeployment());
            } finally {
                HJMPUtils.tryToClose(connection, statement, resultSet);
            }
        }

        void checkResult(final Connection connection, final long oldTS, final int modifiedLines,
                         final boolean optimisticLockingEnabled) throws SQLException {
            if (modifiedLines == 1) {
                return;
            }

            final ResultCheckRow row = readRow(connection);

            if (row.getNumberOfRows() == 0) {
                if (optimisticLockingEnabled) {
                    throw new HJMPException(
                            new HybrisOptimisticLockingFailureException("item " + getPkString() + " doesnt exist in database",
                                    null));
                }
                return;
            }

            if (row.isLocked()) {
                throw new ItemLockedForProcessingException(
                        "Item " + getPkString() + " is locked for processing and cannot be saved or removed");
            }

            if (optimisticLockingEnabled) {
                throw new HJMPException(new HybrisOptimisticLockingFailureException("item pk " + getPkString()
                        + " was modified concurrently - expected database version " + oldTS + " but got " + row
                        .getCurrentHJMPTS()
                        + ", expected blocked = false but got blocked = " + row
                        .isLocked() + ", entity state = " + this, null));
            }
        }

        private ResultCheckRow readRow(final Connection connection) throws SQLException {
            PreparedStatement statement = null;
            ResultSet resultSet = null;
            try {
                statement = connection.prepareStatement("SELECT hjmpTS, sealed FROM " + getTable() + " WHERE PK = ?");
                ((ValueWriter) PK_RW[WRITER]).setValue(statement, 1, pkString);
                resultSet = statement.executeQuery();

                if (resultSet.next()) {
                    final long dbTS = resultSet.getLong(1);
                    final boolean isLocked = resultSet.getBoolean(2);

                    if (resultSet.next()) {
                        throw new HJMPException("item pk " + pkString + " exist multiple times in database");
                    }

                    return new ResultCheckRow(dbTS, isLocked, 1);
                } else {
                    return new ResultCheckRow(0);
                }
            } finally {
                HJMPUtils.tryToClose(statement, resultSet);
            }
        }

        void createEntity(final EJBPropertyRowCache prc, final TypeInfoMap typeInfoMap) {
            Connection connection = null;
            PreparedStatement statement = null;
            try {
                if (!isTransactionBound) {
                    throw new HJMPException("createEntity in EntityState that is not transaction bound");
                }

                // make sure we're using master data source before storing
                getPool().getTenant().forceMasterDataSource();

                // now we should be getting the master data source
                Preconditions.checkArgument(!getPool().getTenant().isSlaveDataSource());


                connection = getPool().getDataSource().getConnection();
                //Log.debug("hjmp","inserting "+pkString+" version "+hjmpTS+" ( state = "+System.identityHashCode(this) );
                final StringBuilder stringBuilder = new StringBuilder("INSERT INTO " + getTable() + " ( " + "hjmpTS");
                int columnCount = 1;
                if (wasChanged(0)) {
                    stringBuilder.append(",").append(getColumn("pkString"));
                    columnCount++;
                }
                if (wasChanged(1)) {
                    stringBuilder.append(",").append(getColumn("creationTimestampInternal"));
                    columnCount++;
                }
                if (wasChanged(2)) {
                    stringBuilder.append(",").append(getColumn("modifiedTimestampInternal"));
                    columnCount++;
                }
                if (wasChanged(3)) {
                    stringBuilder.append(",").append(getColumn("aCLTimestampInternal"));
                    columnCount++;
                }
                if (wasChanged(4)) {
                    stringBuilder.append(",").append(getColumn("typePkString"));
                    columnCount++;
                }
                if (wasChanged(5)) {
                    stringBuilder.append(",").append(getColumn("ownerPkString"));
                    columnCount++;
                }
                if (wasChanged(6)) {
                    stringBuilder.append(",").append(getColumn("propertyTimestampInternal"));
                    columnCount++;
                }
                List[] data = null;
                if (prc != null) {
                    data = PropertyJDBC.getChangeData(connection, typeInfoMap, prc, false);
                    for (int i = 0; i < data[0].size(); i++) {
                        stringBuilder.append(",").append((String) data[0].get(i));
                        columnCount++;
                    }
                }
                stringBuilder.append(" ) VALUES (");
                for (int i = 0; i < columnCount; i++) {
                    stringBuilder.append(i == 0 ? "?" : ",?");
                }
                stringBuilder.append(")");

                if (Config.isPostgreSQLUsed()) {
                    stringBuilder.append(" ON CONFLICT DO NOTHING");
                }

                statement = connection.prepareStatement(stringBuilder.toString());
                ((ValueWriter) LONG_RW[WRITER]).setPrimitive(statement, 1, this.hjmpTS);
                int writeCount = 2; // start with 2 since hjmpTS is at 1
                if (wasChanged(0)) {
                    ((ValueWriter) PK_RW[WRITER]).setValue(statement, writeCount++, this.pkString);
                }
                if (wasChanged(1)) {
                    ((ValueWriter) DATE_RW[WRITER]).setValue(statement, writeCount++, this.creationTimestampInternal);
                }
                if (wasChanged(2)) {
                    ((ValueWriter) DATE_RW[WRITER]).setValue(statement, writeCount++, this.modifiedTimestampInternal);
                }
                if (wasChanged(3)) {
                    ((ValueWriter) LONG_RW[WRITER]).setPrimitive(statement, writeCount++, this.aCLTimestampInternal);
                }
                if (wasChanged(4)) {
                    ((ValueWriter) PK_RW[WRITER]).setValue(statement, writeCount++, this.typePkString);
                }
                if (wasChanged(5)) {
                    ((ValueWriter) PK_RW[WRITER]).setValue(statement, writeCount++, this.ownerPkString);
                }
                if (wasChanged(6)) {
                    ((ValueWriter) LONG_RW[WRITER]).setPrimitive(statement, writeCount++, this.propertyTimestampInternal);
                }
                if (data != null) {
                    for (int i = 0; i < data[0].size(); i++) {
                        ((ValueWriter) data[2].get(i)).setValue(statement, writeCount++, data[1].get(i));
                    }
                }

                final int modifiedLines = statement.executeUpdate();

                if (Config.isPostgreSQLUsed() && modifiedLines == 0) {
                    throw new SQLIntegrityConstraintViolationException(addStatmentQuery(
                            "ERROR: duplicate key value violates unique constraint \"" + getTable() + "_pkey\"", statement));
                }


                if (modifiedLines != 1) {
                    throw new HJMPException("unexpected insert count: " + modifiedLines);
                }
                //Log.debug("hjmp","inserted "+pkString+" version "+hjmpTS );
                if (prc != null) {
                    prc.wroteChanges(true);
                }
                wroteChanges();

                // notify about creation to persistence listeners
                getPool().notifyEntityCreation(this.pkString);
            } catch (final SQLException e) {
                throw new HJMPException(getPool().getDataSource().translateToDataAccessException(e), getDeployment());
            } finally {
                HJMPUtils.tryToClose(connection, statement);
            }
        }

        GenericItemEntityState removeEntity() {
            Connection connection = null;
            PreparedStatement statement = null;

            final boolean optimisticLockingEnabled = HJMPUtils.isOptimisticLockingEnabledForType(getTypePkString());
            try {
                // make sure we're using master data source before storing
                getPool().getTenant().forceMasterDataSource();

                // now we should be getting the master data source
                Preconditions.checkArgument(!getPool().getTenant().isSlaveDataSource());

                connection = getPool().getDataSource().getConnection();


                final StringBuilder stringBuilder = new StringBuilder("DELETE FROM " + getTable() + " WHERE PK = ? ");

                if (optimisticLockingEnabled) {
                    stringBuilder.append("AND hjmpTS = ? ");
                }

                stringBuilder.append("AND (sealed IS NULL OR sealed=0)");

                statement = connection.prepareStatement(stringBuilder.toString());

                ((ValueWriter) PK_RW[WRITER]).setValue(statement, 1, getPkString());

                if (optimisticLockingEnabled) {
                    statement.setLong(2, hjmpTS);
                }

                final GenericItemEntityState newState = getModifiedVersion();

                final int modifiedLines = statement.executeUpdate();
                checkResult(connection, hjmpTS, modifiedLines, optimisticLockingEnabled);

                return newState;
            } catch (final SQLException e) {
                throw new HJMPException(getPool().getDataSource().translateToDataAccessException(e), getDeployment());
            } finally {
                HJMPUtils.tryToClose(connection, statement);
            }
        }

        Map getCacheKeyMap() {
            if (LOG) {
                LOGGER.debug(
                        "GenericItemEntityState getCacheKeyMap -> " + Integer.toHexString(System.identityHashCode(cacheKeyMap))
                                + " " + cacheKeyMap);
                if (cacheKeyMap != null) {
                    final Iterator iter = cacheKeyMap.entrySet().iterator();
                    while (iter.hasNext()) {
                        final Map.Entry next = (Map.Entry) iter.next();
                        LOGGER.debug("	" + next.getKey() + "->" + next.getValue());
                    }
                }
            }
            if (cacheKeyMap == null) {
                if (LOG) {
                    LOGGER.debug("	creating new map");
                }
                cacheKeyMap = new ConcurrentHashMap();
            }
            return cacheKeyMap;
        }

        private String addStatmentQuery(final String value, final PreparedStatement statement) {
            if (statement instanceof PreparedStatementImpl) {
                return value + ". SQL [" + ((PreparedStatementImpl) statement).getPrepStmtPassthruString() + "]";
            }
            return value;
        }

        private String getID() {
            return Integer.toHexString(System.identityHashCode(this));
        }

        @Override
        public String toString() {
            return "GenericItemEntityState(PK=" + getPK() + ",txbound=" + isTransactionBound + ",sid=" + getID() + ",hjmpTS="
                    + hjmpTS + ",hjmpTSBefore=" + hjmpTSFromStartOfTransaction + ")";
        }

        public String toDetailedString() {
            return "GenericItemEntityStateCacheUnit(" + "isTransactionBound=" + isTransactionBound + " " + "sid=" + getID() + " "
                    + "pkString=" + pkString + " " + "theTable=" + getTable() + " " + "creationTimestampInternal="
                    + creationTimestampInternal + " " + "modifiedTimestampInternal=" + modifiedTimestampInternal + " "
                    + "aCLTimestampInternal=" + aCLTimestampInternal + " " + "typePkString=" + typePkString + " " + "ownerPkString="
                    + ownerPkString + " " + "propertyTimestampInternal=" + propertyTimestampInternal + " " + ")";
        }
    }


    public static final class GenericItemEntityStateCacheUnit extends AbstractCacheUnit {
        private final PK thePK;
        private final ItemDeployment depl;

        public GenericItemEntityStateCacheUnit(final Cache cache, final PK pk, final ItemDeployment depl) {
            super(cache);
            this.thePK = pk;
            this.depl = depl;
            Preconditions.checkArgument(checkTypeCodes(Collections.singleton(pk), depl.getTypeCode()));
        }

        @Override
        public Object[] createKey() {
            return new Object[]
                    {Cache.CACHEKEY_HJMP, Cache.CACHEKEY_ENTITY, thePK.getTypeCodeAsString(), thePK};
        }

        @Override
        public int getInvalidationTopicDepth() {
            return 3;
        }

        public GenericItemEntityState getEntityState() throws HJMPException {
            try {
                return (GenericItemEntityState) get();
            } catch (final HJMPException e) {
                throw e;
            } catch (final Exception e) {
                throw new HJMPException("illegal exception type: " + e.getClass().getName(), e);
            }
        }

        public void hintEntityState(final AbstractEntityState newState) {
            super.hintValue(newState);
        }

        @Override
        public Object compute() {
            if (LOG) {
                LOGGER.debug("    computing GenericItemEntityState");
            }
            Connection connection = null;
            try {
                connection = getCache().getTenant().getDataSource().getConnection();
                final EntityState entityState = new GenericItemEntityState(getCache().getTenant().getPersistencePool(), thePK,
                        connection, depl);
                if (LOG) {
                    LOGGER.debug("    done");
                }
                return entityState;
            } catch (final YNoSuchEntityException e) {
                if (LOG) {
                    LOGGER.debug("    no such entity");
                }
                return null;
            } catch (final SQLException e) {
                if (LOG) {
                    LOGGER.debug("    SQLException");
                }
                throw new HJMPException(e);
            } finally {
                try {
                    if (connection != null) {
                        connection.close();
                    }
                } catch (final SQLException e) {
                    throw new HJMPException(e);
                }
            }
        }
    }

}