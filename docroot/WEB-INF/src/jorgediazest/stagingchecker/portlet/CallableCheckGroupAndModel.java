/**
 * Copyright (c) 2015-present Jorge Díaz All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package jorgediazest.stagingchecker.portlet;

import com.liferay.portal.kernel.dao.orm.Criterion;
import com.liferay.portal.kernel.dao.shard.ShardUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.model.Group;
import com.liferay.portal.model.Portlet;
import com.liferay.portal.model.ResourceBlockPermission;
import com.liferay.portal.model.ResourcePermission;
import com.liferay.portal.security.auth.CompanyThreadLocal;
import com.liferay.portal.service.GroupLocalServiceUtil;
import com.liferay.portlet.asset.model.AssetCategory;
import com.liferay.portlet.asset.model.AssetEntry;
import com.liferay.portlet.asset.model.AssetTag;
import com.liferay.portlet.journal.model.JournalArticle;
import com.liferay.portlet.journal.model.JournalArticleResource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import jorgediazest.stagingchecker.ExecutionMode;
import jorgediazest.stagingchecker.model.StagingCheckerModelFactory;

import jorgediazest.util.data.Comparison;
import jorgediazest.util.data.ComparisonUtil;
import jorgediazest.util.data.Data;
import jorgediazest.util.model.Model;
import jorgediazest.util.modelquery.ModelQuery;

/**
 * @author Jorge Díaz
 */
public class CallableCheckGroupAndModel implements Callable<Comparison> {

	public static Set<String> calculateAttributesToCheck(ModelQuery mq) {
		Model model = mq.getModel();

		Set<String> attributesToCheck = new LinkedHashSet<String>();

		attributesToCheck.add(mq.getModel().getPrimaryKeyAttribute());
		attributesToCheck.add("companyId");
		attributesToCheck.add("uuid");

		if (model.isResourcedModel()) {
			attributesToCheck.add("resourcePrimKey");
		}

		attributesToCheck.addAll(
			Arrays.asList(mq.getDataComparator().getExactAttributes()));

		if (AssetTag.class.getName().equals(model.getClassName())) {
			attributesToCheck.add("name");
		}

		return attributesToCheck;
	}

	public CallableCheckGroupAndModel(
		long companyId, long groupId, ModelQuery mq,
		Set<ExecutionMode> executionMode) {

		this.companyId = companyId;
		this.groupId = groupId;
		this.mq = mq;
		this.executionMode = executionMode;
	}

	public Set<String> calculateRelatedAttributesToCheck(Model model) {
		Set<String> relatedAttributesToCheck = new LinkedHashSet<String>();

		String attributeClassPK = "pk";

		if (model.isResourcedModel()) {
			attributeClassPK = "resourcePrimKey";
		}

		String mapping = attributeClassPK+"=classPK";

		relatedAttributesToCheck.add(
			AssetEntry.class.getName() + ":" + mapping +
			":AssetEntry.entryId=entryId, =classPK");
		relatedAttributesToCheck.add(
			AssetEntry.class.getName() + ":" +
			"AssetEntry.entryId=MappingTable:" +
			"AssetEntries_AssetCategories.categoryId=categoryId");
		relatedAttributesToCheck.add(
			AssetCategory.class.getName() + ":" +
			"AssetEntries_AssetCategories.categoryId=categoryId:" +
			" =categoryId,AssetCategory.uuid=uuid");
		relatedAttributesToCheck.add(
			AssetEntry.class.getName() + ":" +
			"AssetEntry.entryId=MappingTable:" +
			"AssetEntries_AssetTags.tagId=tagId");
		relatedAttributesToCheck.add(
			AssetTag.class.getName() + ":" +
			"AssetEntries_AssetTags.tagId=tagId: =tagId,AssetTag.name=name");
		relatedAttributesToCheck.add(
			ResourcePermission.class.getName() + ":" + attributeClassPK +
			"=primKey:[ =primKey,roleId,ownerId,actionIds]:companyId=" +
			companyId + ",name=" + model.getClassName());
		relatedAttributesToCheck.add(
			ResourceBlockPermission.class.getName() + ":resourceBlockId:" +
			"[resourceBlockId,roleId,actionIds]: ");

		if (JournalArticle.class.getName().equals(model.getClassName())) {
			relatedAttributesToCheck.add(
				JournalArticleResource.class.getName() + ":resourcePrimKey:" +
				" =resourcePrimKey,resourceUuid=uuid");
		}

		return relatedAttributesToCheck;
	}

	@Override
	public Comparison call() throws Exception {

		Model model = mq.getModel();

		try {
			CompanyThreadLocal.setCompanyId(companyId);

			ShardUtil.pushCompanyService(companyId);

			if (_log.isInfoEnabled()) {
				_log.info(
					"Model: " + model.getName() + " - CompanyId: " +
						companyId + " - GroupId: " + groupId);
			}

			if (!model.hasAttribute("groupId")) {
				return null;
			}

			StagingCheckerModelFactory mf =
				(StagingCheckerModelFactory)model.getModelFactory();

			Portlet portlet = mf.getPortlet(model.getClassName());

			Group group = GroupLocalServiceUtil.fetchGroup(groupId);

			if (!group.isStagedPortlet(portlet.getPortletId())) {
				if (_log.isDebugEnabled()) {
					_log.debug(
						model.getName() + " is not staged for group " +
							groupId);
				}

				return null;
			}

			long stagingGroupId = group.getStagingGroup().getGroupId();

			Criterion stagingFilter = model.getCompanyGroupFilter(
				companyId, stagingGroupId);

			String[] attributesToCheck = calculateAttributesToCheck(
				mq).toArray(new String[0]);

			String[] relatedAttrToCheck = calculateRelatedAttributesToCheck(
				model).toArray(new String[0]);

			Map<Long, Data> stagingDataMap;

			stagingDataMap = mq.getData(attributesToCheck, stagingFilter);

			mq.addRelatedModelData(
				stagingDataMap, relatedAttrToCheck, stagingFilter);

			Set<Data> stagingData = new HashSet<Data>(stagingDataMap.values());

			Criterion liveFilter = model.getCompanyGroupFilter(
				companyId, groupId);

			Map<Long, Data> liveDataMap;

			liveDataMap = mq.getData(attributesToCheck, liveFilter);

			mq.addRelatedModelData(liveDataMap, relatedAttrToCheck, liveFilter);

			Set<Data> liveData = new HashSet<Data>(liveDataMap.values());

			boolean showBothExact = executionMode.contains(
				ExecutionMode.SHOW_BOTH_EXACT);
			boolean showBothNotExact = executionMode.contains(
				ExecutionMode.SHOW_BOTH_NOTEXACT);
			boolean showOnlyStaging = executionMode.contains(
				ExecutionMode.SHOW_STAGING);
			boolean showOnlyLive = executionMode.contains(
				ExecutionMode.SHOW_LIVE);

			return ComparisonUtil.getComparison(
				model, stagingData, liveData, showBothExact, showBothNotExact,
				showOnlyStaging, showOnlyLive);
		}
		catch (Throwable t) {
			return ComparisonUtil.getError(model, t);
		}
		finally {
			ShardUtil.popCompanyService();
		}
	}

	private static Log _log = LogFactoryUtil.getLog(
		CallableCheckGroupAndModel.class);

	private long companyId = -1;
	private Set<ExecutionMode> executionMode = null;
	private long groupId = -1;
	private ModelQuery mq = null;

}