package se.kth.ict.id2203.components.reconfigurable.rsc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.kth.ict.id2203.components.reconfigurable.rsc.reconfiguration.StopSign;
import se.kth.ict.id2203.ports.reconfigurable.rble.ReconfigurableBallotLeader;
import se.kth.ict.id2203.ports.fpl.FIFOPerfectPointToPointLink;
import se.kth.ict.id2203.ports.fpl.FPp2pSend;
import se.kth.ict.id2203.ports.reconfigurable.cfg.Config;
import se.kth.ict.id2203.ports.reconfigurable.cfg.ConfigPort;
import se.kth.ict.id2203.ports.reconfigurable.cfg.Configuration;
import se.kth.ict.id2203.ports.reconfigurable.rble.ReconfigurableBallotLeaderElection;
import se.kth.ict.id2203.ports.reconfigurable.rsc.*;
import se.kth.ict.id2203.tools.MessageLogger;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Negative;
import se.sics.kompics.Positive;
import se.sics.kompics.address.Address;

import java.util.*;

/**
 * SequenceConsensus - MultiPaxos
 * @author YaMing Wu
 * @version V1.0
 **/

public class MultiPaxos extends ComponentDefinition {

    private static final Logger logger = LoggerFactory.getLogger(MultiPaxos.class);

    private Negative<SequenceConsensus> sc = provides(SequenceConsensus.class);
    private Positive<FIFOPerfectPointToPointLink> fpl = requires(FIFOPerfectPointToPointLink.class);
    private Positive<ReconfigurableBallotLeaderElection> rble = requires(ReconfigurableBallotLeaderElection.class);
    private Positive<ConfigPort> cfgPort = requires(ConfigPort.class);

    // Leader specific
    private List<Object> propCmds;          // Leader's current set of proposed commands (empty set)
    private List<Integer> las;              // Length of longest accepted sequence per acceptor
    private Map<Address, Integer> lds;      // Length of longest known decided sequence per acceptor
    private Integer lc;                     // Length of longest chosen (learned) sequence
    private Map<Address, AckInfo> acks;     // Promise acks per acceptor p |--> (n, v)

    // Replica (including Acceptor and Learner)
    private NlLeaderPair nlLeaderPair;      // Leader's current round number and Leader's Address
    private State state;                    // Replica's current state ({follower, leader}, {prepare, accept, None}) initially (follower, None)
    private Integer nprom;                  // Promise not to accept in lower rounds
    private Integer na;                     // Round number in which a value is accepted
    private List<Object> va;                // Accepted value (empty sequence)
    private Integer ld;                     // Length of the decided sequence (length of empty sequence)

    private Configuration configuration;

    private Integer N;

    private Address self;
    private Set<Address> all;

    private void logLocalVariables() {
        logger.trace(String.format("%d rnote over rsc%d@@" +
                        "cfg:%s, propCmds:%s, las:%s, lds:%s, lc:%s, acks:%s @@" +
                        "%s, state:%s, nprom:%s, na:%s, va:%s, ld:%s" +
                        "@@endrnote",
                System.nanoTime(),
                self.getId(), configuration.getCfg(), propCmds, las, lds, lc, acks, nlLeaderPair, state, nprom, na, va, ld)
        );
        logger.info(String.format(
                        "cfg:%s, %s, %s, las:%s, lds:%s, lc:%s, acks:%s " +
                        "%s, nprom:%s, na:%s, ld:%s",
                configuration.getCfg(), state.role, state.phase, las, lds, lc, acks, nlLeaderPair, nprom, na, ld)
        );
    }

    public MultiPaxos(MultiPaxosInit event) {
        logger.info("Constructing MultiPaxos component.");
        /*
        1: upon event < Init > do
        2:      propCmds := <>
        3:      las := [0]N
        4:      lds := [⊥]N
        5:      lc := 0
        6:      acks := [⊥]N
        7:      (nL; leader) = (0,⊥)
        8:      state = (follower,⊥)
        9:      nprom := 0
        10:     na := 0
        11:     va := <>
        12:     ld := 0
        */

        propCmds = new ArrayList<>();
        las = new ArrayList<>();
        lds = new HashMap<>();
        lc = 0;
        acks = new HashMap<>();

        nlLeaderPair = new NlLeaderPair();
        state = new State();
        nprom = 0;
        na = 0;
        va = new ArrayList<>();
        ld = 0;

        // For reconfiguration
        configuration = event.getConfiguration();
        logger.info("            MultiPaxos: " + configuration);

        // init the Length of longest accepted sequence per acceptor to all '0'
        // leave lds and acks empty
//        Set<Address> allAddresses = event.getAllAddresses();
        Set<Address> allAddresses = event.getConfiguration().getAddresses();

        for (int i = 0; i < allAddresses.size(); i++) {
            las.add(0);
        }

        N = allAddresses.size();
        all = allAddresses;
        self = event.getSelfAddress();

        subscribe(handleBallotLeader, rble);
        subscribe(handleScPropose, sc);
        subscribe(handlePrepareMessage, fpl);
        subscribe(handlePromiseMessage, fpl);
        subscribe(handleAcceptMessage, fpl);
        subscribe(handleAcceptedMessage, fpl);
        subscribe(handleAcceptSyncMessage, fpl);
        subscribe(handleDecideMessage, fpl);

        subscribe(handleNackCfgMessage, fpl);
        subscribe(handleCfgDecideMessage, fpl);

        subscribe(handleScDebug, sc);
    }

    private int rank(Address s) {
        return s.getId() - 1;
    }

    /**
     * Description
     * Find the AckInfo with Maximum 'na', if 'na' are the same, chose the one with longer 'suffix'
     *
     * @param: Map<Address, AckInfo> map
     * @return: AckInfo
     */
    private AckInfo max(Map<Address, AckInfo> map) {
        AckInfo maxAckInfo = null;
        for (Address address : map.keySet()
                ) {
            if (maxAckInfo == null) {
                maxAckInfo = map.get(address);
            } else {
                AckInfo tempAckInfo = map.get(address);
                if (maxAckInfo.getNa() < tempAckInfo.getNa()
                        || ((maxAckInfo.getNa().equals(tempAckInfo.getNa())) && (maxAckInfo.getSfxa().size() < tempAckInfo.getSfxa().size()))) {
                    maxAckInfo = tempAckInfo;
                }
            }
        }

        return maxAckInfo;
    }

    private void doReconfiguration(StopSign stopSign) {
        nprom = 0;
        nlLeaderPair.leader = null;
        nlLeaderPair.nL = 0;

        Configuration cfg = new Configuration(stopSign.getVersion(), stopSign.getPi());
        trigger(new Config(cfg), cfgPort);
    }

    private Handler<ReconfigurableBallotLeader> handleBallotLeader = new Handler<ReconfigurableBallotLeader>() {

        /**
         * Description
         * Handle BallotLeader event.
         * If the event has a leader with high ballot number than the current known leader,
         *     update known leader info to represent this leader.
         * If I am the new leader && the new leader's ballot number is larger than promised round number,
         *     change current my role to LEADER, change my phase to PREPARE;
         *     clear the proposed commands;
         *     clear las (the length of longest accepted sequence per acceptor) to 0;
         *     clear lds (Length of longest known decided sequence per acceptor) to empty;
         *     clear acks (Promise acks per acceptor p) to empty;
         *     send Promise to all other Peers;
         *         it carries: Ballot number of current leader;
         *                     ld (Length of the decided sequence);
         *                     na (Round number in which a value is accepted);
         *     add AckInfo of my self in acks;
         *     add ld of my self in lds;
         *     set nprom to the ballot number of the leader;
         * If I am not the new leader,
         *     if new leader has higher ballot number, switch to FOLLOWER|PREPARE
         *     else: continue as a FOLLOWER of the current LEADER
         *
         * @param: BallotLeader
         * @return: void
         */

        @Override
        public void handle(ReconfigurableBallotLeader event) {
            logLocalVariables();
            MessageLogger.logMessageIn(logger, "Ble", self, "rsc", self, event);

            logger.debug("MultiPaxos, handleBallotLeader ");

            logger.info(String.format("    handleBallotLeader, %s",
                    event));

            if (event.getBallot().getCfg().equals(configuration.getCfg())) {
                if (event.getBallot().getN() > nlLeaderPair.nL) {
                    nlLeaderPair.leader = event.getLeader();
                    nlLeaderPair.nL = event.getBallot().getN();

                    if (self.equals(nlLeaderPair.leader) && nlLeaderPair.nL > nprom) {
                        state.role = State.Role.LEADER;
                        state.phase = State.Phase.PREPARE;

                        abortPropCmds();
                        propCmds.clear();

                        las.clear();
                        for (int i = 0; i < N; i++) {
                            las.add(0);
                        }
                        lds.clear();
                        acks.clear();
                        lc = 0;
                        for (Address p : all
                                ) {
                            if (!self.equals(p)) {
                                PrepareMessage prepareMessage =
                                        new PrepareMessage(self, nlLeaderPair.nL, ld, na, configuration.getCfg());
                                MessageLogger.logMessageOut(logger, "rsc", self, "rsc", p, prepareMessage);
                                trigger(new FPp2pSend(p, prepareMessage), fpl);
                            }
                        }
                        acks.put(event.getLeader(), new AckInfo(na, new ArrayList<>(va.subList(ld, va.size()))));
                        lds.put(self, ld);
                        nprom = nlLeaderPair.nL;
                    } else {
                        state.role = State.Role.FOLLOWER;
                        state.phase = State.Phase.PREPARE;
                    }
                }
            }
            else {
                logger.info(String.format("    handleBallotLeader, config version current %d : event %d",
                        configuration.getCfg(), event.getBallot().getCfg()));
            }

            logLocalVariables();
        }
    };

    private void abortPropCmds() {
        for (Object obj:propCmds
             ) {
            trigger(new ScAbort(obj), sc);
        }
    }

    private Handler<PrepareMessage> handlePrepareMessage = new Handler<PrepareMessage>() {
        @Override
        public void handle(PrepareMessage event) {
            logLocalVariables();
            MessageLogger.logMessageIn(logger, "rsc", event.getSource(), "rsc", self, event);

            logger.debug("MultiPaxos, handlePrepareMessage ");

            logger.info(String.format("    handlePrepareMessage, %s, %s",
                    event.getSource(), event));

            if (configuration.getCfg() < event.getCfg()){
                logger.info(String.format("    handlePrepareMessage, current cfg < received cfg"));
                NackCfgMessage nackCfgMessage = new NackCfgMessage(self, event.getCfg(), configuration.getCfg(), ld);
                MessageLogger.logMessageOut(logger, "rsc", self, "rsc",
                        event.getSource(), nackCfgMessage);
                trigger(nackCfgMessage, fpl);
            } else if (configuration.getCfg().equals(event.getCfg())) {
                logger.info(String.format("    handlePrepareMessage, current cfg == received cfg, nprom:%d, np:%d", nprom, event.getNp()));
                if (nprom < event.getNp()) {
                    nprom = event.getNp();
                    state.role = State.Role.FOLLOWER;
                    state.phase = State.Phase.PREPARE;

                    // TBD. Not sure about this code.
                    // Update the leader information because we are moving to FOLLOWER state
                    nlLeaderPair.nL = event.getNp();
                    nlLeaderPair.leader = event.getSource();

                    List<Object> sfx = new ArrayList<>();
                    // Check whether have more commands accepted, if yes, send the na and suffix back to the leader
                    if (na > event.getNa()) {
                        // original implementation, send suffix(ld, va.size()) back to leader
                        // sfx = new ArrayList<>(va.subList(ld, va.size()));

                        // the original implementation doesn't work if leader crushes and recovered.
                        // the recovered leader has shorter list than the follower, leader.ld maybe 0
                        // try use the shorter ld to generate suffix(min_ld, va.size())
                        logger.info(String.format("    handlePrepareMessage, generating sfx %d, %d, %d",
                                event.getLd(), ld, va.size()));
                        sfx = new ArrayList<>(va.subList(event.getLd() < ld ? event.getLd() : ld, va.size()));
                    } else if (na.equals(event.getNa())){
                        logger.info(String.format("    handlePrepareMessage, generating empty sfx %d, %d, %d",
                                event.getLd(), ld, va.size()));
                        sfx = new ArrayList<>();
                    }

//                trigger < fifop2p, Send | p, [Promise, np, na, sfx, ld] >
                    PromiseMessage promiseMessage = new PromiseMessage(self, event.getNp(), na, sfx, ld);
                    logger.info(String.format("    handlePrepareMessage, sending promise to %s, %s",
                            event.getSource(), promiseMessage));
                    MessageLogger.logMessageOut(logger, "rsc", self, "rsc",
                            event.getSource(), promiseMessage);
                    trigger(new FPp2pSend(event.getSource(), promiseMessage), fpl);
                }
            } else {
                // my configuration is newer than the proposer's configuration
                int i = 0;
                for (Object object:va
                        ) {
                    if (object instanceof StopSign) {
                        if (((StopSign) object).getVersion().equals(event.getCfg())) {
                            i++;
                            break;
                        }
                    }
                    i++;
                }

                logger.debug("            handlePrepareMessage, send CfgDecide ld: " + event.getLd() + "to i: " + i);
                lds.put(event.getSource(), event.getLd());
                List<Object> sfx = new ArrayList<>(va.subList(lds.get(event.getSource()), i));
                CfgDecideMessage cfgDecideMessage = new CfgDecideMessage(event.getSource(), event.getCfg(), ld, nlLeaderPair.nL, sfx);
                MessageLogger.logMessageOut(logger, "rsc", self, "rsc", event.getSource(), cfgDecideMessage);
                trigger(new FPp2pSend(event.getSource(), cfgDecideMessage), fpl);

                // send prepare to source peer
                PrepareMessage prepareMessage =
                        new PrepareMessage(self, nlLeaderPair.nL, ld, na, configuration.getCfg());
                MessageLogger.logMessageOut(logger, "rsc", self, "rsc", event.getSource(), prepareMessage);
                trigger(new FPp2pSend(event.getSource(), prepareMessage), fpl);
            }

            logLocalVariables();
        }
    };

    // upon event < fifp2p, Deliver | a, [Promise, n, na, sfxa, lda ] > do
    private Handler<PromiseMessage> handlePromiseMessage = new Handler<PromiseMessage>() {
        @Override
        public void handle(PromiseMessage event) {
            logLocalVariables();
            MessageLogger.logMessageIn(logger, "rsc", event.getSource(), "rsc", self, event);

            logger.debug("MultiPaxos, handlePromiseMessage ");

            logger.info(String.format("    handlePromiseMessage, %s, %s",
                    event.getSource(), event));

            // The Promise is for current round of leader
            if (nlLeaderPair.nL.equals(event.getNp())
                    && state.role.equals(State.Role.LEADER)
                    && state.phase.equals(State.Phase.PREPARE)) {

                // Add the ackInfo of the source peer into acks
                AckInfo ackInfo = new AckInfo(event.getNa(), event.getSfx());
                acks.put(event.getSource(), ackInfo);

                // Update the ld of the source peer
                lds.put(event.getSource(), event.getLd());

                // If more than half of ackInfo received
                if (acks.size() == ((N + 1) / 2)) {
                    AckInfo maxAckInfo = max(acks); // adopt v

                    // current decided sequence
                    va = va.subList(0, ld);
                    // + Max cmds accepted by other peers
                    va.addAll(maxAckInfo.getSfxa());
                    // + the cmds this peer received
                    va.addAll(propCmds);

                    // set the accepted number of this peer to the length of the new va
                    las.set(rank(self), va.size());

                    // clear the commands set
                    propCmds.clear();

                    // move to LEADER|ACCEPT state
                    state.role = State.Role.LEADER;
                    state.phase = State.Phase.ACCEPT;

                    for (Address p : all
                            ) {
                        if (lds.get(p) != null && !p.equals(self)) {
                            List<Object> sfxp = new ArrayList<>(va.subList(lds.get(p), va.size()));

                            // send AcceptSyncMessage to all the remote peers to let remote peer catch up the new va
                            // the suffix varies depending on the ld of the remote peer
                            AcceptSyncMessage acceptSyncMessage = new AcceptSyncMessage(self, nlLeaderPair.nL, sfxp, lds.get(p));
                            MessageLogger.logMessageOut(logger, "rsc", self, "rsc", p, acceptSyncMessage);
                            trigger(new FPp2pSend(p, acceptSyncMessage), fpl);
                        }
                    }
                }
            } else if (nlLeaderPair.nL.equals(event.getNp())
                    && state.role.equals(State.Role.LEADER)
                    && state.phase.equals(State.Phase.ACCEPT)) {

                // leader in accept phase received Promise message
                // updates the ld of the remote peer
                lds.put(event.getSource(), event.getLd());

                // send AcceptSyncMessage to the source peer and let it to catch up
                List<Object> sfx = new ArrayList<>(va.subList(lds.get(event.getSource()), va.size()));
                AcceptSyncMessage acceptSyncMessage = new AcceptSyncMessage(self, nlLeaderPair.nL, sfx, lds.get(event.getSource()));
                MessageLogger.logMessageOut(logger, "rsc", self, "rsc", event.getSource(), acceptSyncMessage);
                trigger(new FPp2pSend(event.getSource(), acceptSyncMessage), fpl);

                // send Decide message to the source peer
                if (lc != 0) {
                    DecideMessage decideMessage = new DecideMessage(self, ld, nlLeaderPair.nL);
                    MessageLogger.logMessageOut(logger, "rsc", self, "rsc", event.getSource(), decideMessage);
                    trigger(new FPp2pSend(event.getSource(), acceptSyncMessage), fpl);
                }

            }
            logLocalVariables();
        }
    };

    private Handler<AcceptSyncMessage> handleAcceptSyncMessage = new Handler<AcceptSyncMessage>() {
        @Override
        public void handle(AcceptSyncMessage event) {
            logLocalVariables();
            MessageLogger.logMessageIn(logger, "rsc", event.getSource(), "rsc", self, event);

            logger.debug("MultiPaxos, handleAcceptSyncMessage ");

            logger.info(String.format("    handleAcceptSyncMessage, %s, %s",
                    event.getSource(), event));

            // Received AcceptSyncMessage from the current leader
            if (state.role.equals(State.Role.FOLLOWER)
                    && state.phase.equals(State.Phase.PREPARE)) {
                if (nprom.equals(event.getnL())) {

                    // update na
                    na = event.getnL();

                    // update va to have the cmds accepted
                    va = va.subList(0, ld);
                    va.addAll(event.getSfxp());

                    AcceptedMessage acceptedMessage = new AcceptedMessage(self, nlLeaderPair.nL, va.size());
                    MessageLogger.logMessageOut(logger, "rsc", self, "rsc", event.getSource(), acceptedMessage);
                    trigger(new FPp2pSend(event.getSource(), acceptedMessage), fpl);
                    state.role = State.Role.FOLLOWER;
                    state.phase = State.Phase.ACCEPT;
                }
            }
            logLocalVariables();
        }
    };

    private Handler<AcceptMessage> handleAcceptMessage = new Handler<AcceptMessage>() {
        @Override
        public void handle(AcceptMessage event) {
            logLocalVariables();
            MessageLogger.logMessageIn(logger, "rsc", event.getSource(), "rsc", self, event);

            logger.debug("MultiPaxos, handleAcceptMessage ");

            // Address source, Integer ptsPrime, Integer ts, ArrayList<Object> vsuf, Integer l, Integer tPrime
            logger.info(String.format("    handleAcceptMessage, %s, %s",
                    event.getSource(), event));

            if (state.role.equals(State.Role.FOLLOWER) &&
                    state.phase.equals(State.Phase.ACCEPT)) {
                if (nprom.equals(event.getnL())) {
                    va.add(event.getC());
                    AcceptedMessage acceptedMessage = new AcceptedMessage(self, nlLeaderPair.nL, va.size());
                    MessageLogger.logMessageOut(logger, "rsc", self, "rsc", event.getSource(), acceptedMessage);
                    trigger(new FPp2pSend(event.getSource(), acceptedMessage), fpl);
                }
            }
            logLocalVariables();
        }
    };

    private Handler<DecideMessage> handleDecideMessage = new Handler<DecideMessage>() {
        @Override
        public void handle(DecideMessage event) {
            logLocalVariables();
            MessageLogger.logMessageIn(logger, "rsc", event.getSource(), "rsc", self, event);

            logger.debug("MultiPaxos, handleDecideMessage ");

            logger.info(String.format("    handleDecideMessage, %s, %s",
                    event.getSource(), event));

            // Received Decide message for current round
            if (nprom.equals(event.getNL())) {
                // Send ScDecide to the client
                while (ld < event.getLd()) {

                    Object object = va.get(ld);
                    // Check whether StopSign decided
                    if (object instanceof StopSign) {
                        // Handle the StopSign, move to new configuration?
                        logger.debug("MultiPaxos, handleDecideMessage, SS decided: " + object);
                        configuration.setCfg(((StopSign) object).getVersion());
                        configuration.setAddresses(((StopSign) object).getPi());

                        N = configuration.getAddresses().size();
                        all = configuration.getAddresses();

                        doReconfiguration((StopSign)object);
                    }
                    // Not StopSign decided, inform application new value decided
//                    else
                    {
                        ScDecide scDecide = new ScDecide(object);
                        MessageLogger.logMessageOut(logger, "rsc", self, "client", event.getSource(), scDecide);
                        trigger(scDecide, sc);
                    }
                    ld++;
                }
            }
            logLocalVariables();
        }
    };

    private Handler<CfgDecideMessage> handleCfgDecideMessage = new Handler<CfgDecideMessage>() {
        @Override
        public void handle(CfgDecideMessage event) {
            logLocalVariables();
            MessageLogger.logMessageIn(logger, "rsc", event.getSource(), "rsc", self, event);

            logger.debug("MultiPaxos, handleCfgDecideMessage ");

            logger.info(String.format("    handleCfgDecideMessage, %s, %s",
                    event.getSource(), event));

            // CfgDecidedMessage for the current config
            if (configuration.getCfg().equals(event.getCurrentCfg()))
            {
                state.role = State.Role.FOLLOWER;
                state.phase = State.Phase.PREPARE;

                // Sync va with vd from the leader of the new configuration
                va = va.subList(0, ld);
                va.addAll(event.getVd());

                // Send ScDecide to the client
                while (ld < event.getLd()) {

                    Object object = event.getVd().get(ld);
                    // Check whether StopSign decided
                    if (object instanceof StopSign) {
                        // Handle the StopSign, move to new configuration?
                        logger.debug("            handleCfgDecideMessage, SS decided: " + object);
                        configuration.setCfg(((StopSign) object).getVersion());
                        configuration.setAddresses(((StopSign) object).getPi());

                        N = configuration.getAddresses().size();
                        all = configuration.getAddresses();

                        doReconfiguration((StopSign)object);
                    }
                    // Not StopSign decided, inform application new value decided
//                    else
                    {
                        ScDecide scDecide = new ScDecide(object);
                        MessageLogger.logMessageOut(logger, "rsc", self, "client", event.getSource(), scDecide);
                        trigger(scDecide, sc);
                    }
                    ld++;
                }
            }
            logLocalVariables();
        }
    };

    private Handler<AcceptedMessage> handleAcceptedMessage = new Handler<AcceptedMessage>() {
        @Override
        public void handle(AcceptedMessage event) {
            logLocalVariables();
            MessageLogger.logMessageIn(logger, "rsc", event.getSource(), "rsc", self, event);

            logger.debug("MultiPaxos, handleAcceptedMessage ");

            logger.info(String.format("    handleAcceptedMessage, %s, %s",
                    event.getSource(), event));

            if (state.role.equals(State.Role.LEADER) &&
                    state.phase.equals(State.Phase.ACCEPT)) {
                Integer m = event.getLva();

                if (nlLeaderPair.nL.equals(event.getnL())) {
                    las.set(rank(event.getSource()), event.getLva());

                    // If length of learned sequence is less than length of accepted sequence
                    if (lc < m) {
                        int counter = 0;

                        // Check how many peers sent Accepted with la >= m
                        for (Address p : all
                                ) {
                            if (las.get(rank(p)) >= m) {
                                counter++;
                            }
                        }

                        // Received Accepted msg with la >= m from more than half of the peers
                        if (counter >= ((N + 1) / 2)) {
                            // Move lc to m
                            lc = m;
                            // Send Decide Message to all peers in the system
                            for (Address p : all
                                    ) {
                                if (lds.get(p) != null) {
                                    DecideMessage decideMessage = new DecideMessage(self, lc, nlLeaderPair.nL);
                                    MessageLogger.logMessageOut(logger, "rsc", self, "rsc", p, decideMessage);
                                    trigger(new FPp2pSend(p, decideMessage), fpl);
                                }
                            }
                        }
                    }
                }
            }
            logLocalVariables();
        }
    };

    private Handler<ScPropose> handleScPropose = new Handler<ScPropose>() {
        @Override
        public void handle(ScPropose event) {
            logLocalVariables();
            MessageLogger.logMessageIn(logger, "client", self, "rsc", self, event);

            logger.debug("MultiPaxos, ScPropose ");

            logger.info(String.format("    ScPropose, value:%s",
                    event.getValue()));

            if (state.role.equals(State.Role.LEADER) &&
                    state.phase.equals(State.Phase.PREPARE)) {
                propCmds.add(event.getValue());
            } else if (state.role.equals(State.Role.LEADER) &&
                    state.phase.equals(State.Phase.ACCEPT)) {
                va.add(event.getValue());
                las.set(rank(self), las.get(rank(self)) + 1);
                for (Address p : all
                        ) {
                    if (lds.get(p) != null && !p.equals(self)) {
                        AcceptMessage acceptMessage = new AcceptMessage(self, nlLeaderPair.nL, event.getValue());
                        MessageLogger.logMessageOut(logger, "rsc", self, "rsc", p, acceptMessage);
                        trigger(new FPp2pSend(p, acceptMessage), fpl);
                    }
                }
            }
            logLocalVariables();
        }
    };

    private Handler<NackCfgMessage> handleNackCfgMessage = new Handler<NackCfgMessage>() {
        @Override
        public void handle(NackCfgMessage event) {
            logLocalVariables();
            MessageLogger.logMessageIn(logger, "rsc", self, "rsc", self, event);

            logger.debug("MultiPaxos, handleNackCfgMessage ");
            logger.debug("            handleNackCfgMessage " + event);

            if (state.role.equals(State.Role.LEADER)) {
                if (event.getNewCfg().equals(configuration.getCfg())) {
                    // send decide to source peer
                    int i = 0;
                    for (Object object:va
                            ) {
                        if (object instanceof StopSign) {
                            if (((StopSign) object).getVersion().equals(event.getCurrentCfg())) {
                                i++;
                                break;
                            }
                        }
                        i++;
                    }

                    logger.debug("            handleNackCfgMessage, send CfgDecide ld: " + event.getLd() + "to i: " + i);
                    lds.put(event.getSource(), event.getLd());
                    List<Object> sfx = new ArrayList<>(va.subList(lds.get(event.getSource()), i));
                    CfgDecideMessage cfgDecideMessage = new CfgDecideMessage(event.getSource(), event.getCurrentCfg(), ld, nlLeaderPair.nL, sfx);
                    MessageLogger.logMessageOut(logger, "rsc", self, "rsc", event.getSource(), cfgDecideMessage);
                    trigger(new FPp2pSend(event.getSource(), cfgDecideMessage), fpl);

                    // send prepare to source peer
                    PrepareMessage prepareMessage =
                            new PrepareMessage(self, nlLeaderPair.nL, ld, na, configuration.getCfg());
                    MessageLogger.logMessageOut(logger, "rsc", self, "rsc", event.getSource(), prepareMessage);
                    trigger(new FPp2pSend(event.getSource(), prepareMessage), fpl);
                }
            }

            logLocalVariables();
        }
    };

    private Handler<ScDebug> handleScDebug = new Handler<ScDebug>() {
        @Override
        public void handle(ScDebug event) {
            if (event.getValue().equals("1")) {
                logger.debug("Sequence Decided: " + va);
            }
        }
    };
}
