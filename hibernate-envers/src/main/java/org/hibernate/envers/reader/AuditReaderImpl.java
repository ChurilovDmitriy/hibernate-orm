/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2008, Red Hat Middleware LLC or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Middleware LLC.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.envers.reader;
import static org.hibernate.envers.tools.ArgumentsTools.checkNotNull;
import static org.hibernate.envers.tools.ArgumentsTools.checkPositive;

import java.util.*;
import javax.persistence.NoResultException;
import org.hibernate.HibernateException;
import org.hibernate.NonUniqueResultException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.engine.SessionImplementor;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.configuration.AuditConfiguration;
import org.hibernate.envers.exception.AuditException;
import org.hibernate.envers.exception.NotAuditedException;
import org.hibernate.envers.exception.RevisionDoesNotExistException;
import org.hibernate.envers.query.AuditEntity;
import org.hibernate.envers.query.AuditQueryCreator;
import org.hibernate.envers.query.criteria.RevisionTypeAuditExpression;
import org.hibernate.envers.synchronization.AuditProcess;
import org.hibernate.event.EventSource;
import org.hibernate.proxy.HibernateProxy;

/**
 * @author Adam Warski (adam at warski dot org)
 * @author Hern&aacute;n Chanfreau
 * @author Lukasz Antoniak (lukasz dot antoniak at gmail dot com)
 */
public class AuditReaderImpl implements AuditReaderImplementor {
    private final AuditConfiguration verCfg;
    private final SessionImplementor sessionImplementor;
    private final Session session;
    private final FirstLevelCache firstLevelCache;

    public AuditReaderImpl(AuditConfiguration verCfg, Session session,
                              SessionImplementor sessionImplementor) {
        this.verCfg = verCfg;
        this.sessionImplementor = sessionImplementor;
        this.session = session;

        firstLevelCache = new FirstLevelCache();
    }

    private void checkSession() {
        if (!session.isOpen()) {
            throw new IllegalStateException("The associated entity manager is closed!");
        }
    }

    public SessionImplementor getSessionImplementor() {
        return sessionImplementor;
    }

    public Session getSession() {
        return session;
    }

    public FirstLevelCache getFirstLevelCache() {
        return firstLevelCache;
    }

    public <T> T find(Class<T> cls, Object primaryKey, Number revision) throws
            IllegalArgumentException, NotAuditedException, IllegalStateException {
    	
    	return this.find(cls, cls.getName(), primaryKey, revision);
    }
    
    @SuppressWarnings({"unchecked"})
    public <T> T find(Class<T> cls, String entityName, Object primaryKey, Number revision) throws
            IllegalArgumentException, NotAuditedException, IllegalStateException {
        checkNotNull(cls, "Entity class");
        checkNotNull(entityName, "Entity name");
        checkNotNull(primaryKey, "Primary key");
        checkNotNull(revision, "Entity revision");
        checkPositive(revision, "Entity revision");
        checkSession();

        if (!verCfg.getEntCfg().isVersioned(entityName)) {
            throw new NotAuditedException(entityName, entityName + " is not versioned!");
        }

        if (firstLevelCache.contains(entityName, revision, primaryKey)) {
            return (T) firstLevelCache.get(entityName, revision, primaryKey);
        }

        Object result;
        try {
            // The result is put into the cache by the entity instantiator called from the query
            result = createQuery().forEntitiesAtRevision(cls, entityName, revision)
                .add(AuditEntity.id().eq(primaryKey)).getSingleResult();
        } catch (NoResultException e) {
            result = null;
        } catch (NonUniqueResultException e) {
            throw new AuditException(e);
        }

        return (T) result;
    }    

    public List<Number> getRevisions(Class<?> cls, Object primaryKey)
            throws IllegalArgumentException, NotAuditedException, IllegalStateException {
    	
    	return this.getRevisions(cls, cls.getName(), primaryKey);
    }

    @SuppressWarnings({"unchecked"})
    public List<Number> getRevisions(Class<?> cls, String entityName, Object primaryKey)
            throws IllegalArgumentException, NotAuditedException, IllegalStateException {
        // todo: if a class is not versioned from the beginning, there's a missing ADD rev - what then?
        checkNotNull(cls, "Entity class");
        checkNotNull(entityName, "Entity name");
        checkNotNull(primaryKey, "Primary key");
        checkSession();

        if (!verCfg.getEntCfg().isVersioned(entityName)) {
            throw new NotAuditedException(entityName, entityName + " is not versioned!");
        }

        return createQuery().forRevisionsOfEntity(cls, entityName, false, true)
                .addProjection(AuditEntity.revisionNumber())
                .add(AuditEntity.id().eq(primaryKey))
                .getResultList();
    }

    public Date getRevisionDate(Number revision) throws IllegalArgumentException, RevisionDoesNotExistException,
            IllegalStateException{
        checkNotNull(revision, "Entity revision");
        checkPositive(revision, "Entity revision");
        checkSession();

        Query query = verCfg.getRevisionInfoQueryCreator().getRevisionDateQuery(session, revision);

        try {
            Object timestampObject = query.uniqueResult();
            if (timestampObject == null) {
                throw new RevisionDoesNotExistException(revision);
            }

            // The timestamp object is either a date or a long
            return timestampObject instanceof Date ? (Date) timestampObject : new Date((Long) timestampObject);
        } catch (NonUniqueResultException e) {
            throw new AuditException(e);
        }
    }

    public Number getRevisionNumberForDate(Date date) {
        checkNotNull(date, "Date of revision");
        checkSession();

        Query query = verCfg.getRevisionInfoQueryCreator().getRevisionNumberForDateQuery(session, date);

        try {
            Number res = (Number) query.uniqueResult();
            if (res == null) {
                throw new RevisionDoesNotExistException(date);
            }

            return res;
        } catch (NonUniqueResultException e) {
            throw new AuditException(e);
        }
    }

    @SuppressWarnings({"unchecked"})
    public <T> T findRevision(Class<T> revisionEntityClass, Number revision) throws IllegalArgumentException,
            RevisionDoesNotExistException, IllegalStateException {
        checkNotNull(revision, "Entity revision");
        checkPositive(revision, "Entity revision");
        checkSession();

        Set<Number> revisions = new HashSet<Number>(1);
        revisions.add(revision);
        Query query = verCfg.getRevisionInfoQueryCreator().getRevisionsQuery(session, revisions);

        try {
            T revisionData = (T) query.uniqueResult();

            if (revisionData == null) {
                throw new RevisionDoesNotExistException(revision);
            }

            return revisionData;
        } catch (NonUniqueResultException e) {
            throw new AuditException(e);
        }
    }

    @SuppressWarnings({"unchecked"})
    public <T> Map<Number, T> findRevisions(Class<T> revisionEntityClass, Set<Number> revisions) throws IllegalArgumentException,
    IllegalStateException {
		Map<Number, T> result = new HashMap<Number, T>(revisions.size());

    	for (Number revision : revisions) {
            checkNotNull(revision, "Entity revision");
            checkPositive(revision, "Entity revision");
		}
        checkSession();

        Query query = verCfg.getRevisionInfoQueryCreator().getRevisionsQuery(session, revisions);

        try {
            List<T> revisionList = query.list();
            for (T revision : revisionList) {
            	Number revNo = verCfg.getRevisionInfoNumberReader().getRevisionNumber(revision);
       			result.put(revNo, revision);
			}

            return result;
        } catch (HibernateException e) {
            throw new AuditException(e);
        }
    }

    @SuppressWarnings({"unchecked"})
    public List findEntitiesChangedInRevision(Number revision) throws IllegalStateException, IllegalArgumentException, AuditException {
        List<Class> clazz = findEntityTypesChangedInRevision(revision);
        List result = new ArrayList(clazz.size());
        for (Class c : clazz) {
            result.addAll(createQuery().forEntitiesAtCertainRevision(c, revision).getResultList());
        }
        return result;
    }

    @SuppressWarnings({"unchecked"})
    public List findEntitiesChangedInRevision(Number revision, RevisionType revisionType) throws IllegalStateException, IllegalArgumentException, AuditException {
        List<Class> clazz = findEntityTypesChangedInRevision(revision);
        List result = new ArrayList(clazz.size());
        for (Class c : clazz) {
            result.addAll(createQuery().forEntitiesAtCertainRevision(c, revision).add(new RevisionTypeAuditExpression(revisionType, "=")).getResultList());
        }
        return result;
    }

    @SuppressWarnings({"unchecked"})
    public Map<RevisionType, List> findEntitiesChangedInRevisionGroupByRevisionType(Number revision) throws IllegalStateException, IllegalArgumentException, AuditException {
        List<Class> clazz = findEntityTypesChangedInRevision(revision);
        Map<RevisionType, List> result = new HashMap<RevisionType, List>();
        for (RevisionType revisionType : RevisionType.values()) {
            result.put(revisionType, new ArrayList());
            for (Class c : clazz) {
                List list = createQuery().forEntitiesAtCertainRevision(c, revision).add(new RevisionTypeAuditExpression(revisionType, "=")).getResultList();
                result.get(revisionType).addAll(list);
            }
        }
        return result;
    }

    @SuppressWarnings({"unchecked"})
    public List<Class> findEntityTypesChangedInRevision(Number revision) throws IllegalStateException, IllegalArgumentException, AuditException {
        checkNotNull(revision, "Entity revision");
        checkPositive(revision, "Entity revision");
        checkSession();
        if (!verCfg.getGlobalCfg().isTrackEntitiesChangedInRevisionEnabled()) {
            throw new AuditException("This query is designed for Envers default mechanism of tracking entities modified in a given revision."
                                     + " Extend DefaultTrackingModifiedTypesRevisionEntity, utilize @ModifiedEntityNames annotation or set "
                                     + "'org.hibernate.envers.track_entities_changed_in_revision' parameter to true.");
        }
        Query query = verCfg.getRevisionInfoQueryCreator().getEntitiesChangedInRevisionQuery(session, revision);
        Set<String> modifiedEntityNames = new HashSet<String>(query.list());
        List<Class> result = new ArrayList<Class>(modifiedEntityNames.size());
        for (String entityName : modifiedEntityNames) {
            try {
                result.add(Class.forName(entityName));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
        return result;
    }

    @SuppressWarnings({"unchecked"})
	public <T> T getCurrentRevision(Class<T> revisionEntityClass, boolean persist) {
		if (!(session instanceof EventSource)) {
			throw new IllegalArgumentException("The provided session is not an EventSource!");
		}

		// Obtaining the current audit sync
		AuditProcess auditProcess = verCfg.getSyncManager().get((EventSource) session);

		// And getting the current revision data
		return (T) auditProcess.getCurrentRevisionData(session, persist);
	}

	public AuditQueryCreator createQuery() {
        return new AuditQueryCreator(verCfg, this);
    }
	
    public boolean isEntityClassAudited(Class<?> entityClass) {
    	return this.isEntityNameAudited(entityClass.getName());
    }


	public boolean isEntityNameAudited(String entityName) {
        checkNotNull(entityName, "Entity name");
        checkSession();
        return (verCfg.getEntCfg().isVersioned(entityName));
    }	


	public String getEntityName(Object primaryKey, Number revision ,Object entity) throws HibernateException{
        checkNotNull(primaryKey, "Primary key");
        checkNotNull(revision, "Entity revision");
        checkPositive(revision, "Entity revision");
        checkNotNull(entity, "Entity");
        checkSession();

		// Unwrap if necessary
		if(entity instanceof HibernateProxy) {
			entity = ((HibernateProxy)entity).getHibernateLazyInitializer().getImplementation();
		}
		if(firstLevelCache.containsEntityName(primaryKey, revision, entity)) {
			// it's on envers FLC!
			return firstLevelCache.getFromEntityNameCache(primaryKey, revision, entity);
		} else {
			throw new HibernateException(
						"Envers can't resolve entityName for historic entity. The id, revision and entity is not on envers first level cache.");
    }	
}
}