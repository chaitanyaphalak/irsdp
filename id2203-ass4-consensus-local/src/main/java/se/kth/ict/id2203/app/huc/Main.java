/**
 * This file is part of the ID2203 course assignments kit.
 * 
 * Copyright (C) 2009-2013 KTH Royal Institute of Technology
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package se.kth.ict.id2203.app.huc;

import org.apache.log4j.PropertyConfigurator;
import se.kth.ict.id2203.components.beb.BasicBroadcast;
import se.kth.ict.id2203.components.beb.BasicBroadcastInit;
import se.kth.ict.id2203.components.console.JavaConsole;
import se.kth.ict.id2203.components.erb.EagerRb;
import se.kth.ict.id2203.components.erb.EagerRbInit;
import se.kth.ict.id2203.components.hc.Hc;
import se.kth.ict.id2203.components.hc.HcInit;
import se.kth.ict.id2203.components.huc.Huc;
import se.kth.ict.id2203.components.huc.HucInit;
import se.kth.ict.id2203.components.pfd.Pfd;
import se.kth.ict.id2203.components.pfd.PfdInit;
import se.kth.ict.id2203.components.pp2p.DelayLink;
import se.kth.ict.id2203.components.pp2p.DelayLinkInit;
import se.kth.ict.id2203.ports.beb.BestEffortBroadcast;
import se.kth.ict.id2203.ports.console.Console;
import se.kth.ict.id2203.ports.epb.EagerProbabilisticBroadcast;
import se.kth.ict.id2203.ports.erb.EagerReliableBroadcast;
import se.kth.ict.id2203.ports.hc.HierarchicalConsensus;
import se.kth.ict.id2203.ports.huc.HierarchicalUniformConsensus;
import se.kth.ict.id2203.ports.pfd.PerfectFailureDetector;
import se.kth.ict.id2203.ports.pp2p.PerfectPointToPointLink;
import se.sics.kompics.*;
import se.sics.kompics.address.Address;
import se.sics.kompics.launch.Topology;
import se.sics.kompics.network.Network;
import se.sics.kompics.network.netty.NettyNetwork;
import se.sics.kompics.network.netty.NettyNetworkInit;
import se.sics.kompics.timer.Timer;
import se.sics.kompics.timer.java.JavaTimer;

import java.util.Set;

public class Main extends ComponentDefinition {
	static {
		PropertyConfigurator.configureAndWatch("log4j.properties");
	}
	private static int selfId;
	private static String commandScript;
	private Topology topology = Topology.load(System.getProperty("topology"), selfId);

	public static void main(String[] args) {
		selfId = Integer.parseInt(args[0]);
		commandScript = args[1];
		Kompics.createAndStart(Main.class);
	}

	public Main() {
		Address self = topology.getSelfAddress();
		Set<Address> pi = topology.getAllAddresses();

		Component timer = create(JavaTimer.class, Init.NONE);
		Component network = create(NettyNetwork.class, new NettyNetworkInit(topology.getSelfAddress(), 5));
		Component console = create(JavaConsole.class, Init.NONE);
		Component pp2p = create(DelayLink.class, new DelayLinkInit(topology));
		Component beb = create(BasicBroadcast.class, new BasicBroadcastInit(self, pi));
		Component pfd = create(Pfd.class, new PfdInit(self, pi, 2200, 500));
		Component huc = create(Huc.class, new HucInit(self, pi));
		Component erb = create(EagerRb.class, new EagerRbInit(self, pi));
		Component app = create(Application.class, new ApplicationInit(self, pi, commandScript));

		subscribe(handleFault, timer.control());
		subscribe(handleFault, network.control());
		subscribe(handleFault, console.control());
		subscribe(handleFault, pp2p.control());
		subscribe(handleFault, beb.control());
		subscribe(handleFault, pfd.control());
		subscribe(handleFault, huc.control());
		subscribe(handleFault, erb.control());
		subscribe(handleFault, app.control());

		connect(app.required(HierarchicalUniformConsensus.class), huc.provided(HierarchicalUniformConsensus.class));
		connect(app.required(Console.class), console.provided(Console.class));
		connect(app.required(PerfectPointToPointLink.class), pp2p.provided(PerfectPointToPointLink.class));
		connect(app.required(Timer.class), timer.provided(Timer.class));

		connect(huc.required(BestEffortBroadcast.class), beb.provided(BestEffortBroadcast.class));
		connect(huc.required(PerfectFailureDetector.class), pfd.provided(PerfectFailureDetector.class));
		connect(huc.required(PerfectPointToPointLink.class), pp2p.provided(PerfectPointToPointLink.class));
		connect(huc.required(EagerReliableBroadcast.class), erb.provided(EagerReliableBroadcast.class));

		connect(erb.required(BestEffortBroadcast.class), beb.provided(BestEffortBroadcast.class));
		connect(beb.required(PerfectPointToPointLink.class), pp2p.provided(PerfectPointToPointLink.class));

		connect(pfd.required(Timer.class), timer.provided(Timer.class));
		connect(pfd.required(PerfectPointToPointLink.class), pp2p.provided(PerfectPointToPointLink.class));

		connect(pp2p.required(Network.class), network.provided(Network.class));
		connect(pp2p.required(Timer.class), timer.provided(Timer.class));
	}

	private Handler<Fault> handleFault = new Handler<Fault>() {
		@Override
		public void handle(Fault fault) {
			fault.getFault().printStackTrace(System.err);
		}
	};
}
