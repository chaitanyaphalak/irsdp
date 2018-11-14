/**
 * This file is part of the Kompics component model runtime.
 * <p>
 * Copyright (C) 2009 Swedish Institute of Computer Science (SICS) Copyright (C)
 * 2009 Royal Institute of Technology (KTH)
 * <p>
 * Kompics is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package se.sics.kompics;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The <code>ComponentCore</code> class.
 * <p>
 * @author Cosmin Arad <cosmin@sics.se>
 * @author Jim Dowling <jdowling@sics.se>
 * @author Lars Kroll <lkroll@sics.se>
 * @version $Id: $
 */
public abstract class ComponentCore implements Component {

    protected ComponentCore parent;
    public static ThreadLocal<ComponentCore> parentThreadLocal = new ThreadLocal<ComponentCore>();
    protected List<ComponentCore> children;
    protected final ReentrantReadWriteLock childrenLock = new ReentrantReadWriteLock();
    protected Scheduler scheduler;
    protected int wid;

    public ComponentCore getParent() {
        return parent;
    }

    public <P extends PortType> Channel<P> doConnect(Positive<P> positive,
            Negative<P> negative) {
        PortCore<P> positivePort = (PortCore<P>) positive;
        PortCore<P> negativePort = (PortCore<P>) negative;
        ChannelCore<P> channel = new ChannelCoreImpl<P>(positivePort, negativePort,
                negativePort.getPortType());

        positivePort.addChannel(channel);
        negativePort.addChannel(channel);

        return channel;
    }

    public <P extends PortType> Channel<P> doConnect(Positive<P> positive,
            Negative<P> negative, ChannelFilter<?, ?> filter) {
        PortCore<P> positivePort = (PortCore<P>) positive;
        PortCore<P> negativePort = (PortCore<P>) negative;
        ChannelCore<P> channel = new ChannelCoreImpl<P>(positivePort, negativePort,
                negativePort.getPortType());

        Class<? extends Event> eventType = filter.getEventType();
        P portType = positivePort.getPortType();
        if (filter.isPositive()) {
            if (!portType.hasPositive(eventType)) {
                throw new RuntimeException("Port type " + portType
                        + " has no positive " + eventType);
            }
            positivePort.addChannel(channel, filter);
            negativePort.addChannel(channel);
        } else {
            if (!portType.hasNegative(eventType)) {
                throw new RuntimeException("Port type " + portType
                        + " has no negative " + eventType);
            }
            positivePort.addChannel(channel);
            negativePort.addChannel(channel, filter);
        }

        return channel;
    }

    public <P extends PortType> void doDisconnect(Positive<P> positive,
            Negative<P> negative) {
        PortCore<P> positivePort = (PortCore<P>) positive;
        PortCore<P> negativePort = (PortCore<P>) negative;

        positivePort.removeChannelTo(negativePort);
        negativePort.removeChannelTo(positivePort);
    }

    public abstract Negative<ControlPort> createControlPort();

    void doDestroy(Component component) {
        ComponentCore child = (ComponentCore) component;
        if (child.state != State.PASSIVE) {
            Kompics.logger.warn("Destroying a component before it has been stopped is not a good idea: " + child.getComponent());
        }
        child.state = State.DESTROYED;
        try {
            childrenLock.writeLock().lock();
            children.remove(child);

        } finally {
            childrenLock.writeLock().unlock();
        }
    }

    public abstract <T extends ComponentDefinition> Component doCreate(Class<T> definition, Init<T> initEvent);

    public abstract <P extends PortType> Negative<P> createNegativePort(Class<P> portType);

    public abstract <P extends PortType> Positive<P> createPositivePort(Class<P> portType);
    /*
     * === SCHEDULING ===
     */
    public AtomicInteger workCount = new AtomicInteger(0);
    protected SpinlockQueue<PortCore<?>> readyPorts = new SpinlockQueue<PortCore<?>>();

    /**
     * Sets the scheduler.
     * <p>
     * @param scheduler the new scheduler
     */
    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void eventReceived(PortCore<?> port, Event event, int wid) {
        //System.err.println("Received event " + event + " on " + port.getPortType().portTypeClass + " work " + workCount.get());
        port.enqueue(event);
        readyPorts.offer(port);
        int wc = workCount.getAndIncrement();
        if (wc == 0) {
            if (scheduler == null) {
                scheduler = Kompics.getScheduler();
            }
            scheduler.schedule(this, wid);
        }
    }

    public abstract void execute(int wid);
    /*
     * === LIFECYCLE ===
     */
    protected Component.State state = Component.State.PASSIVE;

    public Component.State getState() {
        return state;
    }
}
