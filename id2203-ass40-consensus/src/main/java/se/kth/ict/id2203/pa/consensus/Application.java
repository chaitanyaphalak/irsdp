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
package se.kth.ict.id2203.pa.consensus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kth.ict.id2203.ports.ac.*;
import se.kth.ict.id2203.ports.console.Console;
import se.kth.ict.id2203.ports.console.ConsoleLine;
import se.kth.ict.id2203.ports.pp2p.PerfectPointToPointLink;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Kompics;
import se.sics.kompics.Positive;
import se.sics.kompics.Start;
import se.sics.kompics.address.Address;
import se.sics.kompics.timer.ScheduleTimeout;
import se.sics.kompics.timer.Timer;

public final class Application extends ComponentDefinition {

	private static final Logger logger = LoggerFactory.getLogger(Application.class);

	private Positive<Timer> timer = requires(Timer.class);
	private Positive<Console> console = requires(Console.class);
	private Positive<PerfectPointToPointLink> pp2p = requires(PerfectPointToPointLink.class);
	private Positive<AcPort> uc = requires(AcPort.class);

	private List<String> commands;
	private boolean blocking;

	private boolean proposing = false;
	private int proposalValue = 0;
	private boolean retryProposal = false;
	private Address self;

	public Application(ApplicationInit event) {
		subscribe(handleStart, control);
		subscribe(handleContinue, timer);
		subscribe(handleConsoleInput, console);
		subscribe(handlePp2pMessage, pp2p);
		subscribe(handleDecide, uc);
//		subscribe(handleAbort, uc);

		commands = new ArrayList<String>(Arrays.asList(event.getCommandScript().split(":")));
        commands.add("$DONE");
        blocking = false;
        self = event.getSelfAddress();
	}

	private Handler<Start> handleStart = new Handler<Start>() {
		@Override
		public void handle(Start event) {
			doNextCommand();
		}
	};

	private Handler<ApplicationContinue> handleContinue = new Handler<ApplicationContinue>() {
		@Override
		public void handle(ApplicationContinue event) {
			logger.info("Woke up from sleep");
			blocking = false;
			doNextCommand();
		}
	};

	private Handler<ConsoleLine> handleConsoleInput = new Handler<ConsoleLine>() {
		@Override
		public void handle(ConsoleLine event) {
			if (event.getLine().equals("XX")) {
				doShutdown();
			} else {
				commands.addAll(Arrays.asList(event.getLine().trim().split(":")));
				doNextCommand();
			}
		}
	};

	private Handler<Pp2pMessage> handlePp2pMessage = new Handler<Pp2pMessage>() {
		@Override
		public void handle(Pp2pMessage event) {
			logger.info("Received perfect message {}", event.getMessage());
		}
	};

	private Handler<AcDecide> handleDecide = new Handler<AcDecide>() {
		@Override
		public void handle(AcDecide event) {
			proposing = false;
			logger.info("Got decision for Instance {} with value {}", event.getConsensusId(), event.getValue());
		}
	};

//	private Handler<AcAbort> handleAbort = new Handler<AcAbort>() {
//		@Override
//		public void handle(AcAbort event) {
//			logger.info("Got abort");
//			proposing = false;
//			if (retryProposal) {
//				try {
//					Thread.sleep((long) (Math.random() * 1000));
//				} catch (InterruptedException e) {
//					e.printStackTrace();
//				}
//				doPropose(proposalValue, true);
//			}
//		}
//	};

	private final void doNextCommand() {
		while (!blocking && !commands.isEmpty()) {
            doCommand(commands.remove(0));
		}
	}

	private void doCommand(String cmd) {
		if (cmd.startsWith("P")) {
            List<String> pCmd = new ArrayList<String>(Arrays.asList(cmd.split("-")));
            int instanceId = Integer.parseInt(pCmd.get(0).substring(1));
            int value = Integer.parseInt(pCmd.get(1));

			doPropose(instanceId, value, false);
		} else if (cmd.startsWith("C")) {
            List<String> pCmd = new ArrayList<String>(Arrays.asList(cmd.split("-")));
            int instanceId = Integer.parseInt(pCmd.get(0).substring(1));
            int value = Integer.parseInt(pCmd.get(1));
			doPropose(instanceId, value,true);
		} else if (cmd.startsWith("S")) {
			doSleep(Integer.parseInt(cmd.substring(1)));
		} else if (cmd.startsWith("X")) {
			doShutdown();
		} else if (cmd.equals("help")) {
			doHelp();
		} else if (cmd.equals("$DONE")) {
			logger.info("DONE ALL OPERATIONS");
		} else {
			logger.info("Bad command: '{}'. Try 'help'", cmd);
		}
	}

	private final void doHelp() {
		logger.info("Available commands: P<n>, C<n>, S<n>, help, X");
		logger.info("Pn: propose integer value 'n' to abortable consensus");
		logger.info("Cn: propose integer value 'n' to abortable consensus, and retry until value is decided");
		logger.info("Sn: sleeps 'n' milliseconds before the next command");
		logger.info("help: shows this help message");
		logger.info("X: terminates this process");
	}

	private void doPropose(int instanceId, int value, boolean retry) {
		if (proposing) {
			logger.info("Already have an outstanding proposal");
		} else {
			proposing = true;
			proposalValue = value;
			retryProposal = retry;
			logger.info("Proposing instance {} value {}", instanceId, value);
			trigger(new AcPropose(instanceId, value), uc);
		}
	}

	private void doSleep(long delay) {
		logger.info("Sleeping {} milliseconds...", delay);

		ScheduleTimeout st = new ScheduleTimeout(delay);
		st.setTimeoutEvent(new ApplicationContinue(st));
		trigger(st, timer);
		
		blocking = true;
	}

	private void doShutdown() {
		System.out.println("2DIE");
		System.out.close();
		System.err.close();
		Kompics.shutdown();
		blocking = true;
	}
}
