package ca.uhn.fhir.jpa.dao;

/*
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2015 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.hl7.fhir.instance.model.IBaseResource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.jpa.entity.BaseHasResource;
import ca.uhn.fhir.jpa.entity.ResourceTable;
import ca.uhn.fhir.jpa.util.StopWatch;
import ca.uhn.fhir.model.api.IQueryParameterAnd;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.api.ResourceMetadataKeyEnum;
import ca.uhn.fhir.model.api.TagList;
import ca.uhn.fhir.model.dstu.composite.ResourceReferenceDt;
import ca.uhn.fhir.model.dstu.resource.OperationOutcome;
import ca.uhn.fhir.model.dstu.valueset.IssueSeverityEnum;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.model.primitive.InstantDt;
import ca.uhn.fhir.model.valueset.BundleEntryTransactionOperationEnum;
import ca.uhn.fhir.rest.method.MethodUtil;
import ca.uhn.fhir.rest.method.QualifiedParamList;
import ca.uhn.fhir.rest.server.IBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.util.FhirTerser;

import com.google.common.collect.ArrayListMultimap;

public class FhirSystemDao extends BaseFhirDao implements IFhirSystemDao {
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(FhirSystemDao.class);

	@PersistenceContext()
	private EntityManager myEntityManager;

	@Transactional(propagation = Propagation.REQUIRED)
	@Override
	public List<IResource> transaction(List<IResource> theResources) {
		ourLog.info("Beginning transaction with {} resources", theResources.size());
		long start = System.currentTimeMillis();

		Set<IdDt> allIds = new HashSet<IdDt>();

		for (int i = 0; i < theResources.size(); i++) {
			IResource res = theResources.get(i);
			if (res.getId().hasIdPart() && !res.getId().hasResourceType()) {
				res.setId(new IdDt(toResourceName(res.getClass()), res.getId().getIdPart()));
			}

			/*
			 * Ensure that the bundle doesn't have any duplicates, since this causes all kinds of weirdness
			 */
			if (res.getId().hasResourceType() && res.getId().hasIdPart()) {
				IdDt nextId = res.getId().toUnqualifiedVersionless();
				if (!allIds.add(nextId)) {
					throw new InvalidRequestException("Transaction bundle contains multiple resources with ID: " + nextId);
				}
			}
		}

		FhirTerser terser = getContext().newTerser();

		int creations = 0;
		int updates = 0;

		Map<IdDt, IdDt> idConversions = new HashMap<IdDt, IdDt>();

		List<ResourceTable> persistedResources = new ArrayList<ResourceTable>();

		List<IResource> retVal = new ArrayList<IResource>();
		OperationOutcome oo = new OperationOutcome();
		retVal.add(oo);

		for (int resourceIdx = 0; resourceIdx < theResources.size(); resourceIdx++) {
			IResource nextResource = theResources.get(resourceIdx);

			IdDt nextId = nextResource.getId();
			if (nextId == null) {
				nextId = new IdDt();
			}

			String resourceName = toResourceName(nextResource);
			BundleEntryTransactionOperationEnum nextResouceOperationIn = ResourceMetadataKeyEnum.ENTRY_TRANSACTION_OPERATION.get(nextResource);
			if (nextResouceOperationIn == null && hasValue(ResourceMetadataKeyEnum.DELETED_AT.get(nextResource))) {
				nextResouceOperationIn = BundleEntryTransactionOperationEnum.DELETE;
			}

			String matchUrl = ResourceMetadataKeyEnum.LINK_SEARCH.get(nextResource);
			Set<Long> candidateMatches = null;
			if (StringUtils.isNotBlank(matchUrl)) {
				candidateMatches = processMatchUrl(matchUrl, nextResource.getClass());
			}

			ResourceTable entity;
			if (nextResouceOperationIn == BundleEntryTransactionOperationEnum.CREATE) {
				entity = null;
			} else if (nextResouceOperationIn == BundleEntryTransactionOperationEnum.UPDATE || nextResouceOperationIn == BundleEntryTransactionOperationEnum.DELETE) {
				if (candidateMatches == null || candidateMatches.size() == 0) {
					if (nextId == null || StringUtils.isBlank(nextId.getIdPart())) {
						throw new InvalidRequestException(getContext().getLocalizer().getMessage(FhirSystemDao.class, "transactionOperationFailedNoId", nextResouceOperationIn.name()));
					}
					entity = tryToLoadEntity(nextId);
					if (entity == null) {
						if (nextResouceOperationIn == BundleEntryTransactionOperationEnum.UPDATE) {
							ourLog.debug("Attempting to UPDATE resource with unknown ID '{}', will CREATE instead", nextId);
						} else if (candidateMatches == null) {
							throw new InvalidRequestException(getContext().getLocalizer().getMessage(FhirSystemDao.class, "transactionOperationFailedUnknownId", nextResouceOperationIn.name(), nextId));
						} else {
							ourLog.debug("Resource with match URL [{}] already exists, will be NOOP", matchUrl);
							ResourceMetadataKeyEnum.ENTRY_TRANSACTION_OPERATION.put(nextResource, BundleEntryTransactionOperationEnum.NOOP);
							persistedResources.add(null);
							retVal.add(nextResource);
							continue;
						}
					}
				} else if (candidateMatches.size() == 1) {
					entity = loadFirstEntityFromCandidateMatches(candidateMatches);
				} else {
					throw new InvalidRequestException(getContext().getLocalizer().getMessage(FhirSystemDao.class, "transactionOperationWithMultipleMatchFailure", nextResouceOperationIn.name(), matchUrl, candidateMatches.size()));
				}
			} else if (nextResouceOperationIn == BundleEntryTransactionOperationEnum.NOOP) {
				throw new InvalidRequestException(getContext().getLocalizer().getMessage(FhirSystemDao.class, "incomingNoopInTransaction"));
			} else if (nextId.isEmpty()) {
				entity = null;
			} else {
				entity = tryToLoadEntity(nextId);
			}

			BundleEntryTransactionOperationEnum nextResouceOperationOut;
			if (entity == null) {
				nextResouceOperationOut = BundleEntryTransactionOperationEnum.CREATE;
				entity = toEntity(nextResource);
				if (nextId.isEmpty() == false && nextId.getIdPart().startsWith("cid:")) {
					ourLog.debug("Resource in transaction has ID[{}], will replace with server assigned ID", nextId.getIdPart());
				} else if (nextResouceOperationIn == BundleEntryTransactionOperationEnum.CREATE) {
					if (nextId.isEmpty() == false) {
						ourLog.debug("Resource in transaction has ID[{}] but is marked for CREATE, will ignore ID", nextId.getIdPart());
					}
					if (candidateMatches != null) {
						if (candidateMatches.size() == 1) {
							ourLog.debug("Resource with match URL [{}] already exists, will be NOOP", matchUrl);
							BaseHasResource existingEntity = loadFirstEntityFromCandidateMatches(candidateMatches);
							IResource existing = (IResource) toResource(existingEntity);
							ResourceMetadataKeyEnum.ENTRY_TRANSACTION_OPERATION.put(existing, BundleEntryTransactionOperationEnum.NOOP);
							persistedResources.add(null);
							retVal.add(existing);
							continue;
						}
						if (candidateMatches.size() > 1) {
							throw new InvalidRequestException(getContext().getLocalizer().getMessage(FhirSystemDao.class, "transactionOperationWithMultipleMatchFailure", BundleEntryTransactionOperationEnum.CREATE.name(), matchUrl, candidateMatches.size()));
						}
					}
				} else {
					createForcedIdIfNeeded(entity, nextId);
				}
				myEntityManager.persist(entity);
				if (entity.getForcedId() != null) {
					myEntityManager.persist(entity.getForcedId());
				}
				creations++;
				ourLog.info("Resource Type[{}] with ID[{}] does not exist, creating it", resourceName, nextId);
			} else {
				nextResouceOperationOut = nextResouceOperationIn;
				if (nextResouceOperationOut == null) {
					nextResouceOperationOut = BundleEntryTransactionOperationEnum.UPDATE;
				}
				updates++;
				ourLog.info("Resource Type[{}] with ID[{}] exists, updating it", resourceName, nextId);
			}

			persistedResources.add(entity);
			retVal.add(nextResource);
			ResourceMetadataKeyEnum.ENTRY_TRANSACTION_OPERATION.put(nextResource, nextResouceOperationOut);
		}

		ourLog.info("Flushing transaction to database");
		myEntityManager.flush();

		for (int i = 0; i < persistedResources.size(); i++) {
			ResourceTable entity = persistedResources.get(i);

			String resourceName = toResourceName(theResources.get(i));
			IdDt nextId = theResources.get(i).getId();

			IdDt newId;

			if (entity == null) {
				newId = retVal.get(i + 1).getId().toUnqualifiedVersionless();
			} else {
				newId = entity.getIdDt().toUnqualifiedVersionless();
			}

			if (nextId == null || nextId.isEmpty()) {
				ourLog.info("Transaction resource (with no preexisting ID) has been assigned new ID[{}]", nextId, newId);
			} else {
				if (nextId.toUnqualifiedVersionless().equals(newId)) {
					ourLog.info("Transaction resource ID[{}] is being updated", newId);
				} else {
					if (!nextId.getIdPart().startsWith("#")) {
						nextId = new IdDt(resourceName + '/' + nextId.getIdPart());
						ourLog.info("Transaction resource ID[{}] has been assigned new ID[{}]", nextId, newId);
						idConversions.put(nextId, newId);
					}
				}
			}

		}

		for (IResource nextResource : theResources) {
			List<ResourceReferenceDt> allRefs = terser.getAllPopulatedChildElementsOfType(nextResource, ResourceReferenceDt.class);
			for (ResourceReferenceDt nextRef : allRefs) {
				IdDt nextId = nextRef.getReference();
				if (idConversions.containsKey(nextId)) {
					IdDt newId = idConversions.get(nextId);
					ourLog.info(" * Replacing resource ref {} with {}", nextId, newId);
					nextRef.setReference(newId);
				} else {
					ourLog.debug(" * Reference [{}] does not exist in bundle", nextId);
				}
			}
		}

		ourLog.info("Re-flushing updated resource references and extracting search criteria");

		for (int i = 0; i < theResources.size(); i++) {
			IResource resource = theResources.get(i);
			ResourceTable table = persistedResources.get(i);
			if (table == null) {
				continue;
			}

			InstantDt deletedInstantOrNull = ResourceMetadataKeyEnum.DELETED_AT.get(resource);
			Date deletedTimestampOrNull = deletedInstantOrNull != null ? deletedInstantOrNull.getValue() : null;
			if (deletedInstantOrNull == null && ResourceMetadataKeyEnum.ENTRY_TRANSACTION_OPERATION.get(resource) == BundleEntryTransactionOperationEnum.DELETE) {
				deletedTimestampOrNull = new Date();
				ResourceMetadataKeyEnum.DELETED_AT.put(resource, new InstantDt(deletedTimestampOrNull));
			}

			updateEntity(resource, table, table.getId() != null, deletedTimestampOrNull);
		}

		long delay = System.currentTimeMillis() - start;
		ourLog.info("Transaction completed in {}ms with {} creations and {} updates", new Object[] { delay, creations, updates });

		oo.addIssue().setSeverity(IssueSeverityEnum.INFORMATION).setDetails("Transaction completed in " + delay + "ms with " + creations + " creations and " + updates + " updates");

		notifyWriteCompleted();

		return retVal;
	}

	private boolean hasValue(InstantDt theInstantDt) {
		return theInstantDt != null && theInstantDt.isEmpty() == false;
	}

	private ResourceTable tryToLoadEntity(IdDt nextId) {
		ResourceTable entity;
		try {
			Long pid = translateForcedIdToPid(nextId);
			entity = myEntityManager.find(ResourceTable.class, pid);
		} catch (ResourceNotFoundException e) {
			entity = null;
		}
		return entity;
	}

	private ResourceTable loadFirstEntityFromCandidateMatches(Set<Long> candidateMatches) {
		return myEntityManager.find(ResourceTable.class, candidateMatches.iterator().next());
	}

	private Set<Long> processMatchUrl(String theMatchUrl, Class<? extends IBaseResource> theResourceType) {
		RuntimeResourceDefinition resourceDef = getContext().getResourceDefinition(theResourceType);

		SearchParameterMap paramMap = new SearchParameterMap();
		List<NameValuePair> parameters;
		try {
			parameters = URLEncodedUtils.parse(new URI(theMatchUrl), "UTF-8");
		} catch (URISyntaxException e) {
			throw new InvalidRequestException("Failed to parse match URL[" + theMatchUrl + "] - Error was: " + e.toString());
		}

		ArrayListMultimap<String, QualifiedParamList> nameToParamLists = ArrayListMultimap.create();
		for (NameValuePair next : parameters) {
			String paramName = next.getName();
			String qualifier = null;
			for (int i = 0; i < paramMap.size(); i++) {
				switch (paramName.charAt(i)) {
				case '.':
				case ':':
					qualifier = paramName.substring(i);
					paramName = paramName.substring(0, i);
					i = Integer.MAX_VALUE;
					break;
				}
			}

			QualifiedParamList paramList = QualifiedParamList.splitQueryStringByCommasIgnoreEscape(qualifier, next.getValue());
			nameToParamLists.put(paramName, paramList);
		}

		for (String nextParamName : nameToParamLists.keySet()) {
			RuntimeSearchParam paramDef = resourceDef.getSearchParam(nextParamName);
			if (paramDef == null) {
				throw new InvalidRequestException("Failed to parse match URL[" + theMatchUrl + "] - Resource type " + resourceDef.getName() + " does not have a parameter with name: " + nextParamName);
			}

			List<QualifiedParamList> paramList = nameToParamLists.get(nextParamName);
			IQueryParameterAnd<?> param = MethodUtil.parseQueryParams(paramDef, nextParamName, paramList);
			paramMap.add(nextParamName, param);
		}

		IFhirResourceDao<? extends IResource> dao = getDao(theResourceType);
		Set<Long> ids = dao.searchForIdsWithAndOr(paramMap);

		return ids;
	}

	@Override
	public IBundleProvider history(Date theSince) {
		StopWatch w = new StopWatch();
		IBundleProvider retVal = super.history(null, null, theSince);
		ourLog.info("Processed global history in {}ms", w.getMillisAndRestart());
		return retVal;
	}

	@Override
	public TagList getAllTags() {
		StopWatch w = new StopWatch();
		TagList retVal = super.getTags(null, null);
		ourLog.info("Processed getAllTags in {}ms", w.getMillisAndRestart());
		return retVal;
	}

	@Override
	public Map<String, Long> getResourceCounts() {
		CriteriaBuilder builder = myEntityManager.getCriteriaBuilder();
		CriteriaQuery<Tuple> cq = builder.createTupleQuery();
		Root<?> from = cq.from(ResourceTable.class);
		cq.multiselect(from.get("myResourceType").as(String.class), builder.count(from.get("myResourceType")).as(Long.class));
		cq.groupBy(from.get("myResourceType"));

		TypedQuery<Tuple> q = myEntityManager.createQuery(cq);

		Map<String, Long> retVal = new HashMap<String, Long>();
		for (Tuple next : q.getResultList()) {
			String resourceName = next.get(0, String.class);
			Long count = next.get(1, Long.class);
			retVal.put(resourceName, count);
		}
		return retVal;
	}

}
