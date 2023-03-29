package pt.ulisboa.tecnico.hdsledger.service.services;

import pt.ulisboa.tecnico.hdsledger.communication.LedgerRequestTransfer;
import pt.ulisboa.tecnico.hdsledger.communication.LedgerResponse;
import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.communication.PerfectLink;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;
import pt.ulisboa.tecnico.hdsledger.utilities.ErrorMessage;
import pt.ulisboa.tecnico.hdsledger.utilities.LedgerException;
import pt.ulisboa.tecnico.hdsledger.utilities.RSAEncryption;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.logging.Level;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;

public class LedgerService implements UDPService {

    private static final CustomLogger LOGGER = new CustomLogger(LedgerService.class.getName());
    // Clients configurations
    private final ProcessConfig[] clientConfigs;
    // Node identifier
    private final String nodeId;
    // Node service
    private final NodeService service;
    // Link to communicate with blockchain nodes
    private final PerfectLink link;
    // Map of requests from clients
    private final Map<String, Set<Integer>> clientRequests = new ConcurrentHashMap<>();
    // Thread to run service
    private Thread thread;

    public LedgerService(ProcessConfig[] clientConfigs, String nodeId, NodeService service, PerfectLink link) {
        this.clientConfigs = clientConfigs;
        this.nodeId = nodeId;
        this.service = service;
        this.link = link;
    }

    public Thread getThread() {
        return thread;
    }

    public void killThread() {
        thread.interrupt();
    }

    public Optional<LedgerResponse> requestConsensus(LedgerRequestTransfer request) {

        String clientId = request.getSenderId();
        int messageId = request.getMessageId();
        int requestId = request.getNonce();
        int clientKnownBlockchainSize = request.getKnownBlockchainSize();

        // Check if client has already sent this request
        clientRequests.putIfAbsent(clientId, ConcurrentHashMap.newKeySet());
        boolean isNewMessage = clientRequests.get(clientId).add(messageId);

        LOGGER.log(Level.INFO, "Request for consensus");

        if (isNewMessage) {
            LOGGER.log(Level.INFO, "Starting consensus");

            // Start consensus instance
            int consensusInstance = service.startConsensus(request);
            Map<Integer, String> blockchain;
            for (;;) {
                // Wait for consensus to finish
                blockchain = service.getBlockchain();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (blockchain.size() >= consensusInstance)
                    break;
            }

            LOGGER.log(Level.INFO, MessageFormat.format("{0} - Consensus finished", nodeId));
            LOGGER.log(Level.INFO,
                    MessageFormat.format("{0} - New blockchain: {1}", nodeId, service.getBlockchainAsList()));

            return Optional.of(new LedgerResponse(nodeId, requestId, consensusInstance,
                    service.getBlockchainStartingAtInstance(clientKnownBlockchainSize)));
        }

        LOGGER.log(Level.INFO, "Already started consensus for this request, ignoring");
        return Optional.empty();
    }

    private boolean verifyClientSignature(LedgerRequestTransfer request) {
        Optional<ProcessConfig> clientConfig = Arrays.stream(this.clientConfigs)
                .filter(c -> c.getId().equals(request.getSenderId())).findFirst();
        if (clientConfig.isEmpty())
            throw new LedgerException(ErrorMessage.NoSuchClient);
        return RSAEncryption.verifySignature(request.getValue(), request.getClientSignature(),
                clientConfig.get().getPublicKeyPath());
    }

    @Override
    public void listen() {
        try {
            // Thread to listen on every request
            // This is not thread safe but it's okay because
            // a client only sends one request at a time
            // thread listening for client requests on clientPort {Append, Read}
            new Thread(() -> {
                try {
                    while (true) {

                        Message message = link.receive();

                        // Separate thread to handle each message
                        new Thread(() -> {

                            Optional<LedgerResponse> response = Optional.empty();
                            switch (message.getType()) {
                                case REQUEST -> {

                                    LedgerRequestTransfer request = (LedgerRequestTransfer) message;

                                    if (!verifyClientSignature(request)) {
                                        LOGGER.log(Level.INFO, MessageFormat.format(
                                                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n"
                                              + "@       WARNING: INVALID CLIENT SIGNATURE!      @\n"
                                              + "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n"
                                              + "IT IS POSSIBLE THAT NODE {0} IS DOING SOMETHING NASTY!",
                                                message.getSenderId()));
                                        return;
                                    }

                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format("{0} - Received {1} message from {2}",
                                                    nodeId, request.getValue().equals("") ? "READ" : "APPEND",
                                                    message.getSenderId()));
                                    response = requestConsensus(request);
                                }
                                case ACK -> {
                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format("{0} - Received ACK message from {1}",
                                                    nodeId, message.getSenderId()));
                                }
                                case IGNORE -> {
                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format("{0} - Received IGNORE message from {1}",
                                                    nodeId, message.getSenderId()));
                                }
                                default -> {
                                    throw new LedgerException(ErrorMessage.CannotParseMessage);
                                }
                            }

                            if (response.isEmpty())
                                return;

                            // Reply to a specific client
                            link.send(message.getSenderId(), response.get());

                        }).start();
                    }
                } catch (IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
