package se.kth.ict.id2203.app.reconfigurable.rble;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.kth.ict.id2203.app.epfd.Pp2pMessage;
import se.kth.ict.id2203.ports.reconfigurable.cfg.Config;
import se.kth.ict.id2203.ports.reconfigurable.cfg.ConfigPort;
import se.kth.ict.id2203.ports.reconfigurable.cfg.Configuration;
import se.kth.ict.id2203.ports.console.Console;
import se.kth.ict.id2203.ports.console.ConsoleLine;
import se.kth.ict.id2203.ports.pp2p.PerfectPointToPointLink;
import se.kth.ict.id2203.ports.pp2p.Pp2pSend;
import se.kth.ict.id2203.ports.reconfigurable.rble.ReconfigurableBallotLeader;
import se.kth.ict.id2203.ports.reconfigurable.rble.ReconfigurableBallotLeaderElection;
import se.sics.kompics.*;
import se.sics.kompics.address.Address;
import se.sics.kompics.timer.ScheduleTimeout;
import se.sics.kompics.timer.Timer;

import java.util.*;

public final class Application extends ComponentDefinition {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    private Positive<Timer> timer = requires(Timer.class);
    private Positive<Console> console = requires(Console.class);
    private Positive<PerfectPointToPointLink> pp2p = requires(PerfectPointToPointLink.class);
    private Positive<ReconfigurableBallotLeaderElection> rble = requires(ReconfigurableBallotLeaderElection.class);
    private Positive<ConfigPort> cfgPort = requires(ConfigPort.class);

    private Address self;
    private Set<Address> neighbors;
    private Map<Integer, Address> all;

    private ArrayList<String> commands;
    private boolean blocking;

    public Application(ApplicationInit event) {
        subscribe(handleStart, control);
        subscribe(handleContinue, timer);
        subscribe(handleConsoleInput, console);
        subscribe(handlePp2pMessage, pp2p);
        subscribe(handleLeader, rble);

        self = event.getSelfAddress();

        all = new HashMap<>();
        for (Address address : event.getAllAddresses()
                ) {
            all.put(address.getId(), address);
        }

        neighbors = new TreeSet<Address>(event.getConfiguration().getAddresses());
        neighbors.remove(self);

        commands = new ArrayList<String>(Arrays.asList(event.getCommandScript().split(":")));
        commands.add("$DONE");
        blocking = false;
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

    private Handler<ReconfigurableBallotLeader> handleLeader = new Handler<ReconfigurableBallotLeader>() {
        @Override
        public void handle(ReconfigurableBallotLeader event) {
            logger.info(String.format("Process %s is the leader of config %s with ballot number %s",
                    event.getLeader(), event.getBallot().getCfg(), event.getBallot().getN()));
        }
    };

    private final void doNextCommand() {
        while (!blocking && !commands.isEmpty()) {
            doCommand(commands.remove(0));
        }
    }

    private void doCommand(String cmd) {
        if (cmd.startsWith("P")) {
            doPerfect(cmd.substring(1));
        } else if (cmd.startsWith("S")) {
            doSleep(Integer.parseInt(cmd.substring(1)));
        } else if (cmd.startsWith("X")) {
            doShutdown();
        } else if (cmd.startsWith("C")) {
            doReconfiguration(cmd.substring(1));
        } else if (cmd.equals("help")) {
            doHelp();
        } else if (cmd.equals("$DONE")) {
            logger.info("DONE ALL OPERATIONS");
        } else {
            logger.info("Bad command: '{}'. Try 'help'", cmd);
        }
    }

    private void doReconfiguration(String strings) {
        logger.info("Reconfiguration " + strings);

        String[] args = strings.split("@");

        Integer cfgId = Integer.parseInt(args[0]);

        HashSet<Address> newConfiguredAddress = new HashSet<>();

        boolean firstParameter = true;
        for (String string : args
                ) {
            try {
                if (!firstParameter) {
                    Integer id = Integer.parseInt(string);

                    if (all.get(id) != null) {
                        newConfiguredAddress.add(all.get(id));
                    }
                } else {
                    firstParameter = false;
                }
            } catch (RuntimeException e) {
            }
        }

        Configuration cfg = new Configuration(cfgId, newConfiguredAddress);
        trigger(new Config(cfg), cfgPort);
    }

    private final void doHelp() {
        logger.info("Available commands: P<m>, S<n>, C<n>@<n>@<n>@<n>, help, X");
        logger.info("Pm: sends perfect message 'm' to all neighbors");
        logger.info("Pm: sends perfect message 'm' to all neighbors");
        logger.info("C<n>@<a>@<b>@<c>: reconfigure the topology, <n> is the config id, " +
                "<a><b><c> are the process id being configured in this configuration");
        logger.info("Sn: sleeps 'n' milliseconds before the next command");
        logger.info("help: shows this help message");
        logger.info("X: terminates this process");
    }

    private final void doPerfect(String message) {
        for (Address neighbor : neighbors) {
            logger.info("Sending perfect message {} to {}", message, neighbor);
            trigger(new Pp2pSend(neighbor, new Pp2pMessage(self, message)), pp2p);
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
