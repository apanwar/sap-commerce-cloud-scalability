/**
 *
 */
package com.sap.dto.b2bunit.service;

import de.hybris.platform.core.PK;
import de.hybris.platform.core.model.ItemModel;


/**
 * @author Davide
 *
 */
public interface DtoCacheService
{

	boolean canApplyDtoCacheOnPopulator(String cacheName);


	Object getDtoCached(String cacheName, ItemModel source);


	Object getDtoCached(String cacheName, ItemModel source, String options);


	void storeDtoCached(String cacheName, ItemModel source, Object target);


	void storeDtoCached(String cacheName, String options, ItemModel source, Object target);


	void invalidCache(PK pk);


	void invalidCache(String cacheName);






}
