/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIESOR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.jpa.support.impl;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.apache.aries.jpa.supplier.EmSupplier;
import org.osgi.service.coordinator.Coordination;
import org.osgi.service.coordinator.Coordinator;
import org.osgi.service.coordinator.Participant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread safe way to use an EntityManager.
 * 
 * Before the EMF is closed the close() method has to be called to make
 * sure all EMs are closed.
 */
public class EMSupplierImpl implements EmSupplier {
    private static final long DEFAULT_SHUTDOWN_WAIT_SECS = 10;
    private static Logger LOG = LoggerFactory.getLogger(EMSupplierImpl.class);
    private EntityManagerFactory emf;
    private AtomicBoolean shutdown;
    private long shutdownWaitTime = DEFAULT_SHUTDOWN_WAIT_SECS;
    private TimeUnit shutdownWaitTimeUnit = TimeUnit.SECONDS;

    private Set<EntityManager> emSet;
    private CountDownLatch emsToShutDown;
    private Coordinator coordinator;

    public EMSupplierImpl(final EntityManagerFactory emf, Coordinator coordinator) {
        this.emf = emf;
        this.coordinator = coordinator;
        this.shutdown = new AtomicBoolean(false);
        this.emSet = Collections.newSetFromMap(new ConcurrentHashMap<EntityManager, Boolean>());
    }

    private EntityManager createEm(EntityManagerFactory emf) {
        LOG.debug("Creating EntityManager");
        EntityManager em = emf.createEntityManager();
        emSet.add(em);
        return em;
    }

    /**
     * Allows to retrieve one EntityManager per thread. Creates the EntityManager if none is present for the
     * thread. If the EM on the thread is closed it will be replaced by a fresh one.
     */
    @Override
    public EntityManager get() {
        Coordination coordination = getTopCoordination();
        EntityManager em = getEm(coordination);
        if (coordination != null && em == null) {
            em = createEm(emf);
            emSet.add(em);
            coordination.getVariables().put(EntityManager.class, em);
            coordination.addParticipant(new Participant() {
                
                @Override
                public void failed(Coordination coordination) throws Exception {
                    ended(coordination);
                }
                
                @Override
                public void ended(Coordination coordination) throws Exception {
                    EntityManager em = getEm(coordination);
                    em.close();
                    emSet.remove(em);
                    if (shutdown.get()) {
                        emsToShutDown.countDown();
                    }
                }
            });
        }
        return em;
    }
    
    Coordination getTopCoordination() {
        Coordination coordination = coordinator.peek();
        while (coordination != null && coordination.getEnclosingCoordination() != null) {
            coordination = coordination.getEnclosingCoordination();
        }
        return coordination;
    }

    /**
     * Get EntityManager from outer most Coordination that holds an EM
     * @param coordination
     * @return
     */
    private EntityManager getEm(Coordination coordination) {
        if (coordination == null) {
            return null;
        } else {
            return (EntityManager)coordination.getVariables().get(EntityManager.class);
        }
    }

    @Override
    public void preCall() {
        coordinator.begin("jpa", 0);
    }

    @Override
    public void postCall() {
        coordinator.pop().end();
    }

    /**
     * Closes all EntityManagers that were opened by this Supplier.
     * It will first wait for the EMs to be closed by the running threads.
     * If this times out it will shutdown the remaining EMs itself.
     * @return true if clean close, false if timeout occured
     */
    public boolean close() {
        synchronized (this) {
            shutdown.set(true);
            emsToShutDown = new CountDownLatch(emSet.size());
        }
        try {
            emsToShutDown.await(shutdownWaitTime, shutdownWaitTimeUnit);
        } catch (InterruptedException e) {
        }
        return shutdownRemaining();
    }

    private synchronized boolean shutdownRemaining() {
        boolean clean = (emSet.size() == 0); 
        if  (!clean) {
            LOG.warn("{} EntityManagers still open after timeout. Shutting them down now", emSet.size());
        }
        for (EntityManager em : emSet) {
            closeEm(em);
        }
        emSet.clear();
        return clean;
    }

    private void closeEm(EntityManager em) {
        try {
            if (em.isOpen()) {
                em.close();
            }
        } catch (Exception e) {
            LOG.warn("Error closing EntityManager", e);
        }
    }

    public void setShutdownWait(long shutdownWaitTime, TimeUnit shutdownWaitTimeUnit) {
        this.shutdownWaitTime = shutdownWaitTime;
        this.shutdownWaitTimeUnit = shutdownWaitTimeUnit;
    }

}
