# Configuring the execution of the queries to Read-Only Replica
Since read-replica feature has been released, SAP Commerce platform can direct the Flexible Search query to read-replica if read replica in enable it the session context (`ctx.enable.fs.on.read-replica`).

However, to enable this execution each flexible query execution is required to be bound with the the read-replica context. It requires extension of the classes where such queries are executing.

To simplify it, in the [azureoptimizationsreadonly](./azureoptimizationsreadonly) extension, there is an AoP aspect [ReadOnlyReplicaAspect](./azureoptimizationsreadonly/src/com/sap/cx/boosters/azureoptimizationsreadonly/aspect/ReadOnlyReplicaAspect.java) introduced to configure the methods for what the queries must execute on read only replica.

## Enabling/Disabling the execution of queries on Read-Only replica
The property `azureoptimizationsreadonly.read-replica-aspect.enabled` controls the activation of the Read-Only aspect at global level at startup. By default it is enabled. If it is required to be disabled then the property value can be updated as mentioned below followed by a restart:
`azureoptimizationsreadonly.read-replica-aspect.enabled=false`

## Enabling the queries executing in the context of a method on Read-Only replica
There are certain steps to be followed to enable the execution of a query in a method's context on read-only replica. The steps are:
1. Add a method with an around advice in the `ReadOnlyReplicaAspect` class for your method and. For example:
```java
    @Around("execution(* de.hybris.platform.commerceservices.category.impl.DefaultCommerceCategoryService.getPathsForCategory(..))")
    public Object aroundDefaultCommerceCategoryServiceGetPathsInternal(final ProceedingJoinPoint pjp) throws Throwable {
        return activateReadReplicaIfNeeded("DefaultCommerceCategoryService.getPathsInternal", pjp);
    }
```
2. The return value of it must be a method call to the method `activateReadReplicaIfNeeded`. The argument passed in the method call must be the of the format `<Class Name>.<Method Name>`.
3. Now, you must add a property to enable the execution of the queries in the context of this method on read only replica as:
```
azuresqlenhancements.read-replica-aspect.enabled.methods.<Some Unique Number>=<Class Name>.<Method Name>

```

## Using Read Replica source for data model queries
The standard read-ponly replica feature enables the execution of regular queries to read-only replica. However, the primary database will still be used to retrieve the entity by pk and we will still have the following query executed on the primary:
`select * from xxx where pk=xxx` when the list of results will be converted to Model.

Here is an example of call:

```
	at com.sap.db.jdbc.ConnectionSapDB.prepareStatement(Ljava/lang/String;)Ljava/sql/PreparedStatement;(ConnectionSapDB.java:246)
	at de.hybris.platform.jdbcwrapper.ConnectionImpl.prepareStatement(Ljava/lang/String;)Ljava/sql/PreparedStatement;(ConnectionImpl.java:630)
	at de.hybris.platform.persistence.GenericBMPBean$GenericItemEntityState.<init>(Lde/hybris/platform/persistence/framework/PersistencePool;Lde/hybris/platform/core/PK;Ljava/sql/Connection;Lde/hybris/platform/core/ItemDeployment;)V(GenericBMPBean.java:1210)
	at de.hybris.platform.persistence.GenericBMPBean$GenericItemEntityStateCacheUnit.compute()Ljava/lang/Object;(GenericBMPBean.java:1965)
	at de.hybris.platform.cache.AbstractCacheUnit.privateGetNoLock()Ljava/lang/Object;(AbstractCacheUnit.java:305)
	at de.hybris.platform.cache.AbstractCacheUnit.privateGet()Ljava/lang/Object;(AbstractCacheUnit.java:278)
	at de.hybris.platform.cache.AbstractCacheUnit.get()Ljava/lang/Object;(AbstractCacheUnit.java:180)
	at de.hybris.platform.persistence.GenericBMPBean$GenericItemEntityStateCacheUnit.getEntityState()Lde/hybris/platform/persistence/GenericBMPBean$GenericItemEntityState;(GenericBMPBean.java:1937)
	at de.hybris.platform.persistence.GenericBMPBean.ejbFindByPrimaryKey(Lde/hybris/platform/core/PK;)Lde/hybris/platform/core/PK;(GenericBMPBean.java:845)
	at de.hybris.platform.persistence.framework.PersistencePool.findEntityByPK(Ljava/lang/String;Lde/hybris/platform/core/PK;)Lde/hybris/platform/persistence/framework/EntityProxy;(PersistencePool.java:288)
	at de.hybris.platform.persistence.SystemEJB.findRemoteObjectByPKInternal(Lde/hybris/platform/core/PK;)Lde/hybris/platform/persistence/ItemRemote;(SystemEJB.java:102)
	at de.hybris.platform.persistence.SystemEJB.findRemoteObjectByPK(Lde/hybris/platform/core/PK;)Lde/hybris/platform/persistence/ItemRemote;(SystemEJB.java:84)
	at de.hybris.platform.persistence.SystemEJB.findRemoteObjectsByPK(Ljava/util/Collection;Ljava/util/Set;Z)Ljava/util/Map;(SystemEJB.java:129)
	at de.hybris.platform.core.WrapperFactory.getCachedItems(Lde/hybris/platform/cache/Cache;Ljava/util/Collection;Ljava/util/Set;ZZ)Ljava/util/Collection;(WrapperFactory.java:333)
	at de.hybris.platform.core.LazyLoadItemList.loadPage(Ljava/util/List;)Ljava/util/List;(LazyLoadItemList.java:228)
	at de.hybris.platform.servicelayer.search.impl.LazyLoadModelList.loadPage(Ljava/util/List;)Ljava/util/List;(LazyLoadModelList.java:86)
	at de.hybris.platform.core.LazyLoadItemList.switchPage(I)Lde/hybris/platform/core/LazyLoadItemList$BufferedPage;(LazyLoadItemList.java:217)
	at de.hybris.platform.core.LazyLoadItemList.switchBufferedPageNoLock(I)Lde/hybris/platform/core/LazyLoadItemList$BufferedPage;(LazyLoadItemList.java:473)
	at de.hybris.platform.core.LazyLoadItemList.switchBufferedPageSynchronized(I)Lde/hybris/platform/core/LazyLoadItemList$BufferedPage;(LazyLoadItemList.java:465)
	- locked <0x00000007bca017e8> (a de.hybris.platform.servicelayer.search.impl.LazyLoadModelList)
	at de.hybris.platform.core.LazyLoadItemList.switchBufferedPage(I)Lde/hybris/platform/core/LazyLoadItemList$BufferedPage;(LazyLoadItemList.java:460)
	at de.hybris.platform.core.LazyLoadItemList.getOrSwitchBufferedPage(IZ)Lde/hybris/platform/core/LazyLoadItemList$BufferedPage;(LazyLoadItemList.java:451)
	at de.hybris.platform.core.LazyLoadItemList.getOrSwitchBufferedPage(I)Lde/hybris/platform/core/LazyLoadItemList$BufferedPage;(LazyLoadItemList.java:431)
	at de.hybris.platform.core.LazyLoadItemList.getBuffered(I)Ljava/lang/Object;(LazyLoadItemList.java:109)
	at de.hybris.platform.core.LazyLoadItemList.get(I)Ljava/lang/Object;(LazyLoadItemList.java:95)
	at java.util.Collections$UnmodifiableList.get(I)Ljava/lang/Object;(Collections.java:1309)
	at de.hybris.platform.servicelayer.search.impl.DefaultFlexibleSearchService.searchUnique(Lde/hybris/platform/servicelayer/search/FlexibleSearchQuery;)Ljava/lang/Object;(DefaultFlexibleSearchService.java:295)
```

The objective of this feature is to redirect this query also to read replica.

### Enabling feature
* By default the feature is disabled and to enable it, you need to add following property:

  `azureoptimizationsreadonly.read-replica.entity.enabled=true`

* We have implemented an additional functionality that allows the retrieval of model queries from the read-replica if the table is included in the following list, which accepts a comma-separated value. If we modify the list below, the specific model query will be directed to the read-replica, regardless of the read-replica session context.

  `azureoptimizationsreadonly.read-replica.entity.tables.list=products,pricerows`

### Limitation
This feature exclusively relies on the read-replica session context and does not support the enabled read-replica feature through categorized query hint.