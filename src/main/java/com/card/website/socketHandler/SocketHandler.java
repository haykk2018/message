package com.card.website.socketHandler;

import com.card.website.domain.Node;
import com.card.website.domain.Parent;
import com.card.website.repository.NodeRepository;
import com.card.website.repository.ParentRepository;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Configurable

public class SocketHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SocketHandler.class);
    static WebSocketSession curSession;
    static String parentId;
    static HashMap<String, WebSocketSession> sessionByParId = new HashMap<String, WebSocketSession>();
    static public HashMap<String, Boolean> statusForEveryParId = new HashMap<String, Boolean>();
    static public HashMap<String, ArrayList<String>> messagesQueListByParId = new HashMap<String, ArrayList<String>>();


    List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    @Autowired
    private NodeRepository nodeRepository;
    @Autowired
    private ParentRepository parentRepository;

    public void sendMessagesFromQue() throws IOException {

        for (String parentId : statusForEveryParId.keySet()) {
            // check all parents if any of they is status is true(ready to receive message) and have message > then send message
            if (statusForEveryParId.get(parentId) && messagesQueListByParId.get(parentId) != null && messagesQueListByParId.get(parentId).size() > 0) {
                sendMessage(parentId, messagesQueListByParId.get(parentId).get(0));
                messagesQueListByParId.get(parentId).remove(0);
                statusForEveryParId.put(parentId, false);
            }
        }
    }

    public void sendMessage(String parentId, String message) throws IOException {

        WebSocketSession session = sessionByParId.get(parentId);

        if (session != null && session.isOpen()) {

            synchronized (session) {
                session.sendMessage(new TextMessage(message));
            }

        }
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message)
            throws InterruptedException, IOException {

//        Thread.sleep(1000); // simulated delay
        String recycledMessage;
        Parent parent = new Parent();
        Map mapValue = null;
        try {
            mapValue = new Gson().fromJson(message.getPayload(), Map.class);
            recycledMessage = mapValue.get("character").toString();
        } catch (com.google.gson.JsonSyntaxException ex) {
            recycledMessage = message.getPayload();
        }
//        session.sendMessage(new TextMessage("Hello " + message.getPayload() + " !"));
//		for(WebSocketSession webSocketSession : sessions) {
//			Map value = new Gson().fromJson(message.getPayload(), Map.class);
//			webSocketSession.sendMessage(new TextMessage("Hello " + message.getPayload() + " !"));
//		}
        switch (recycledMessage) {
            case "routingResponse":
                ArrayList receivedList = (ArrayList) mapValue.get("idChildes");
                ArrayList<Node> nodes = new ArrayList<Node>();
                parentId = mapValue.get("idParent").toString();
                statusForEveryParId.put(parentId, true);
                parent = parentRepository.findParentByParentId(parentId) != null ? parentRepository.findParentByParentId(parentId) : parent;
                parent.setParentId(parentId);
                for (Object n : receivedList) {
                    Node node = new Node();
                    node.setChildId(n.toString());
                    node.setParent(parent);
                    nodes.add(node);
                }
                parent.setNodes(nodes);
                parentRepository.save(parent);
                break;
            case "parentId":
                parentId = mapValue.get("value").toString();
                sessionByParId.put(parentId, session);
                statusForEveryParId.put(parentId, true);
                if (parentRepository.findParentByParentId(parentId) == null) {
                    parent.setParentId(parentId);
                    parentRepository.save(parent);
                }
                break;
            case "child":
                break;
            //   every receive message  waiting = false,  its manning you can paste new message
            case "confirmMessage":
                break;
            default:
                //   from  which parent come response > we set that parent id status true, so that can sand the message again
                for (Map.Entry<String, WebSocketSession> entry : sessionByParId.entrySet()) {
                    if (entry.getValue() == session) {
                        statusForEveryParId.put(entry.getKey(), true);
                    }
                }
                break;
        }

    }

    @Scheduled(fixedRate = 9000)
    public void messageScheduler() throws IOException {
        String message;
        //        first find all which must be open
        Iterable<Node> toOpenNodes = nodeRepository.findAllByStartDailyTimeBeforeAndEndDailyTimeAfterAndOpenedFalse(LocalTime.now(), LocalTime.now());

        if (toOpenNodes != null) {

            for (Node node : toOpenNodes) {
                node.setOpened(true);
                nodeRepository.save(node);
                message = "sp" + node.getChildId() + "Pop" + node.getChildId() + "G" + node.getParent().getParentId();
                if (messagesQueListByParId.containsKey(node.getParent().getParentId())) {
                    messagesQueListByParId.get(node.getParent().getParentId()).add(message);
                } else {
                    messagesQueListByParId.put(node.getParent().getParentId(), new ArrayList<String>(Arrays.asList(message)));
                }

            }
        }
        //        second find all which must to close
        Iterable<Node> toCloseNodes = nodeRepository.findAllByStartDailyTimeAfterOrEndDailyTimeBeforeAndOpenedIsTrue(LocalTime.now(), LocalTime.now());

        if (toCloseNodes != null) {

            for (Node node : toCloseNodes) {
                node.setOpened(false);
                nodeRepository.save(node);
                message = "sp" + node.getChildId() + "Pcl" + node.getChildId() + "G" + node.getParent().getParentId();
                if (messagesQueListByParId.containsKey(node.getParent().getParentId())) {
                    messagesQueListByParId.get(node.getParent().getParentId()).add(message);
                } else {
                    messagesQueListByParId.put(node.getParent().getParentId(), new ArrayList<String>(Arrays.asList(message)));
                }
            }
        }

        sendMessagesFromQue();

        LOG.info("Hello from our simple scheduled method");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        //the messages will be broadcasted to all users.
        sessions.add(session);
        this.curSession = session;
    }
}