package OrderBook;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;

import com.google.gson.Gson;

import Eseguibili.Server.SockMapValue;
// Classe che rappresenta l'order book del mercato: gestendo ordine di bid e ask, processare marketOrders stopOrders e LimitOrders

public class OrderBook {
    public ConcurrentSkipListMap<Integer, Bookvalue> askMap;
    public int spread;
    public int lastOrderID = 0;
    public ConcurrentLinkedQueue<StopValue> stopOrders;
    public ConcurrentSkipListMap<Integer, Bookvalue> bidMap;

    public OrderBook(ConcurrentSkipListMap<Integer,Bookvalue> askMap, int spread, ConcurrentLinkedQueue<StopValue> stopOrders, ConcurrentSkipListMap<Integer,Bookvalue> bidMap){
        this.askMap = askMap;
        this.spread = spread;
        this.bidMap = bidMap;
        this.stopOrders = stopOrders;
        updateOrderBook();
    }
        
    /*Metodo notifica all'utente sullo stato dell'ordine tramite UDP con parametri;
        socketMap: Mappa degli utenti connessi e delle relative informazioni socket
        user: nome utente da notificare
        orderID: ID dell'ordine da notificare
        type: tipo di ordine (bid/ask)
        orderType: tipo di ordine (limit/market/stop)
        size: dimensione dell'ordine
        price: prezzo dell'ordine
        */


    public void notifyUser(ConcurrentSkipListMap<String, SockMapValue> socketMap, String user, int orderID, String type, String orderType, int size, int price) {
        System.out.println("Notifying user: " + user + " about order ID: " + orderID + ", Type: " + type + ", Order Type: " + orderType + ", Size: " + size + ", Price: " + price);
        
        // Estrazione delle informazioni socket dell'utente
        SockMapValue socketInfo = socketMap.get(user);
        
        if(socketInfo != null){
            try(DatagramSocket sock = new DatagramSocket()){
                //Creazione dell'oggetto Trade
                Trade trade = new Trade(orderID, type, orderType, size, price);
                // Utilizzo di una libreria JSON come Gson per la serializzazione
                Gson gson = new Gson();
                String json = gson.toJson(trade);
                // Conversione del JSON in un array di byte
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                // Creazione di un pacchetto Datagram con i dati e indirizzo del server
                DatagramPacket packet = new DatagramPacket(data, data.length, socketInfo.address, socketInfo.port);
                // Invio del pacchetto
                sock.send(packet);

            } catch (Exception e){
                System.err.println("NotifyUser() Error: " + e.getMessage());
            }
        } else {
            System.out.println("User not online, message not sent.");
        }
    }

    // VERSIONE LEGACY PER Map<String, String> (se necessaria per compatibilità)
    public void notifyUser(Map<String, String> socketMap, String user, int orderID, String type, String orderType, int size, int price) {
        System.out.println("Notifying user: " + user + " about order ID: " + orderID + ", Type: " + type + ", Order Type: " + orderType + ", Size: " + size + ", Price: " + price);
        System.out.println("Warning: Using legacy socketMap format - UDP notification may not work correctly");
        
        // Implementazione semplificata per compatibilità - potrebbe non funzionare correttamente
        String socketInfo = socketMap.get(user);
        if(socketInfo != null) {
            System.out.println("Found user in legacy socketMap: " + socketInfo);
            // Qui dovresti implementare la logica per parsare le informazioni socket dal formato String
        } else {
            System.out.println("User not found in legacy socketMap.");
        }
    }
    
    // Metodo per restituire gli username presenti nella lista degli utenti
    public ConcurrentLinkedQueue<String> getUsers(ConcurrentLinkedQueue<UserBook> list) {
        ConcurrentLinkedQueue<String> users = new ConcurrentLinkedQueue<>();
        for (UserBook user : list) {
            users.add(user.username);
        }
        return users;
    }

    //Metodo per controllare tutti gli stopOrder per verificare se qualcuno di essi debba essere eseguito in base ai prezzi di mercato correnti
    public synchronized void checkStopOrders(ConcurrentSkipListMap<String, SockMapValue> socketMap) {
        Iterator<StopValue> iterator = stopOrders.iterator();

        while (iterator.hasNext()) {
            StopValue order = iterator.next();
            boolean triggerCondition = false;
            ConcurrentLinkedQueue<String> userList = null;

            if (order.type.equals("ask")) {
                if (!bidMap.isEmpty()) {
                    Integer bestBidPrice = bidMap.firstKey();
                    if (order.stopPrice <= bestBidPrice.longValue()) {
                        userList = getUsers(bidMap.get(bestBidPrice).userList);
                        triggerCondition = userList.stream().anyMatch(u -> !u.equals(order.username));
                    }
                }
            } else if (order.type.equals("bid")) {
                if (!askMap.isEmpty()) {
                    Integer bestAskPrice = askMap.firstKey();
                    if (order.stopPrice >= bestAskPrice.longValue()) {
                        userList = getUsers(askMap.get(bestAskPrice).userList);
                        triggerCondition = userList.stream().anyMatch(u -> !u.equals(order.username));
                    }
                }
            }

            if (triggerCondition) {
                int res = tryMarketOrder(order.type, order.size, order.username, "stop", socketMap);
                lastOrderID--; // già assegnato in insertStopOrder()

                if (res != -1) {
                    System.out.printf("%s's StopOrder processed successfully. Order: %s\n", order.username, order);
                } else {
                    System.out.printf("%s's StopOrder was processed but failed. Order: %s\n", order.username, order);
                    notifyUser(socketMap, order.username, order.orderID, order.type, "stop", 0, 0);
                }

                iterator.remove();
            }
        }
    }

    // Aggiunto metodo tryMarketOrder mancante
    public synchronized int tryMarketOrder(String type, int size, String username, String orderType, ConcurrentSkipListMap<String, SockMapValue> socketMap) {
        if ("bid".equals(type)) {
            // Market order di acquisto - prende il miglior prezzo ask disponibile
            if (!askMap.isEmpty()) {
                int bestAskPrice = askMap.firstKey();
                return tryBidOrder(size, bestAskPrice, username, socketMap);
            }
        } else if ("ask".equals(type)) {
            // Market order di vendita - prende il miglior prezzo bid disponibile
            if (!bidMap.isEmpty()) {
                int bestBidPrice = bidMap.firstKey();
                return tryAskOrder(size, bestBidPrice, username, socketMap);
            }
        }
        return -1; // Ordine fallito
    }

    public synchronized void loadBidOrder(int size, int price, String user, int orderID) {
        UserBook newuser = new UserBook(size, user, orderID);
        //Se prezzo già esiste
        if (bidMap.containsKey(price)) { 
            Bookvalue oldValue = bidMap.get(price);
            ConcurrentLinkedQueue<UserBook> newList = new ConcurrentLinkedQueue<>(oldValue.userList);
            newList.add(newuser); //Aggiungo il nuovo utente alla lista
            int newSize = oldValue.size + size; //Aggiorno la size
            Bookvalue newValue = new Bookvalue(newSize, newSize * price, newList);
            bidMap.replace(price, newValue); //Sostituisco il vecchio Bookvalue con il nuovo
        } else {
            ConcurrentLinkedQueue<UserBook> newuserList = new ConcurrentLinkedQueue<>();
            newuserList.add(newuser);
            Bookvalue newValue = new Bookvalue(size, price * size, newuserList);
            bidMap.put(price, newValue);
        }
    }

    //Metodo per elaborare un limitOrder di tipo bid - CORRETTO IL FLUSSO DI CONTROLLO
    public synchronized int tryBidOrder(int size, int price, String user, ConcurrentSkipListMap<String, SockMapValue> socketMap) {
        int remainingSize = size;
        int orderID = updateLastOrderID();

        for (Map.Entry<Integer, Bookvalue> entry : askMap.entrySet()) {
            int askPrice = entry.getKey();
            Bookvalue askValue = entry.getValue();

            if (askPrice <= price) { // Se il prezzo di vendita è inferiore o uguale al prezzo di acquisto
                int matchedSize = Math.min(remainingSize, askValue.size); 
                remainingSize -= matchedSize;

                // Notifica gli utenti coinvolti
                for (UserBook userBook : askValue.userList) {
                    notifyUser(socketMap, userBook.username, orderID, "bid", "limit", matchedSize, askPrice);
                }

                // Rimuove l'ordine dalla askMap se completamente soddisfatto
                if (askValue.size == matchedSize) {
                    askMap.remove(askPrice);
                } else {
                    // Aggiorna la size dell'ordine nella askMap
                    askValue.size -= matchedSize;
                    askValue.total -= matchedSize * askPrice;
                    askValue.userList.removeIf(u -> u.username.equals(user));
                    askMap.put(askPrice, askValue);
                }

                if (remainingSize == 0) { //Ordine completato
                    System.out.println("Order number " + orderID + " fully matched.");
                    updateOrderBook();
                    return orderID;
                }
            }
        }
        
        // CORRETTO: Questo blocco ora è fuori dal for loop
        if (remainingSize > 0) {
            loadBidOrder(remainingSize, price, user, orderID);
            if (remainingSize == size) {
                System.out.println("Order number " + orderID + " unmatched: " + remainingSize + " placed in the orderBook");
            } else {
                System.out.println("Order number " + orderID + " was partially completed; the remaining size of " + remainingSize + " was added to the orderBook");
            }
        }
        updateOrderBook();
        return orderID;
    }

    //Metodo per caricare un ordine di vendita (ask) nella askMap
    public synchronized void loadAskOrder(int size, int price, String user, int orderID){
        UserBook newuser = new UserBook(size, user, orderID);
        //Se il prezzo è già esistente
        if (askMap.containsKey(price)){
            Bookvalue oldValue = askMap.get(price);
            ConcurrentLinkedQueue<UserBook> newList = new ConcurrentLinkedQueue<>(oldValue.userList);
            newList.add(newuser);
            int newSize = oldValue.size + size;
            Bookvalue newValue = new Bookvalue(newSize, newSize * price, newList);
            askMap.replace(price, newValue);
        } else {
            ConcurrentLinkedQueue<UserBook> newuserList = new ConcurrentLinkedQueue<>();
            newuserList.add(newuser);
            Bookvalue newValue = new Bookvalue(size, price * size, newuserList);
            askMap.put(price, newValue);
        }
    }
           
    // CORRETTO IL VALORE DI RITORNO E IL FLUSSO DI CONTROLLO
    public synchronized int tryAskOrder(int size, int price, String user, ConcurrentSkipListMap<String, SockMapValue> socketMap) {
        int remainingSize = size;
        int orderID = updateLastOrderID();

        for (Map.Entry<Integer, Bookvalue> entry : bidMap.entrySet()) {
            int bidPrice = entry.getKey();
            Bookvalue bidValue = entry.getValue();

            if (bidPrice >= price) { // Se il prezzo di acquisto è superiore o uguale al prezzo di vendita
                int matchedSize = Math.min(remainingSize, bidValue.size);
                remainingSize -= matchedSize;

                // Notifica gli utenti coinvolti
                for (UserBook userBook : bidValue.userList) {
                    notifyUser(socketMap, userBook.username, orderID, "ask", "limit", matchedSize, bidPrice);
                }

                // Rimuove l'ordine dalla bidMap se completamente soddisfatto
                if (bidValue.size == matchedSize) {
                    bidMap.remove(bidPrice);
                } else {
                    // Aggiorna la size dell'ordine nella bidMap
                    bidValue.size -= matchedSize;
                    bidValue.total -= matchedSize * bidPrice;
                    bidValue.userList.removeIf(u -> u.username.equals(user));
                    bidMap.put(bidPrice, bidValue);
                }

                if (remainingSize == 0) { //Ordine completato
                    System.out.println("Order number " + orderID + " fully matched.");
                    updateOrderBook();
                    return orderID; // CORRETTO: Restituisce orderID invece di 0
                }
            }
        }
        
        // CORRETTO: Questo blocco ora è fuori dal for loop
        if (remainingSize > 0) {
            loadAskOrder(remainingSize, price, user, orderID);
            if (remainingSize == size) {
                System.out.println("Order number " + orderID + " unmatched: " + remainingSize + " placed in the orderBook");
            } else {
                System.out.println("Order number " + orderID + " was partially completed; the remaining size of " + remainingSize + " was added to the orderBook");
            }
        }
        updateOrderBook();
        return orderID;
    }
    
    //Algoritmo per eseguire il matching tra ordini ask-bid
    public synchronized int tryMatch(int remainingSize, String user, String userType, ConcurrentLinkedQueue<UserBook> list, String listType, String orderType, int price, int orderID, ConcurrentSkipListMap<String, SockMapValue> socketMap) {
        Iterator<UserBook> iterator = list.iterator();
        while(iterator.hasNext() && remainingSize > 0) {
            UserBook IUser = iterator.next();
            if (!IUser.username.equals(user)) {
                if (IUser.size > remainingSize){
                    IUser.size -= remainingSize;
                    notifyUser(socketMap, IUser.username, IUser.orderID, listType, "limit", remainingSize, price);
                    notifyUser(socketMap, user, orderID, userType, orderType, remainingSize, price);
                    remainingSize = 0;
                
                } else if (IUser.size < remainingSize) {
                    remainingSize -= IUser.size;
                    notifyUser(socketMap, IUser.username, IUser.orderID, listType, "limit", IUser.size, price);
                    notifyUser(socketMap, user, orderID, userType, orderType, IUser.size, price);
                    iterator.remove();
                } else { // IUser.size == remainingSize
                    notifyUser(socketMap, IUser.username, IUser.orderID, listType, "limit", IUser.size, price);
                    notifyUser(socketMap, user, orderID, userType, orderType, remainingSize, price);
                    iterator.remove();
                    remainingSize = 0;
                }
            } else {
                iterator.remove();
                notifyUser(socketMap, IUser.username, IUser.orderID, listType, "limit", IUser.size, price);
                notifyUser(socketMap, user, orderID, userType, orderType, remainingSize, price);
                remainingSize = 0;
            }
        }
        return remainingSize;
    }

    //Metodo per cancellare un ordine, restituisce 100 se l'ordine è stato eliminato correttamente else 101
    public synchronized int cancelOrder(int orderID, String onlineUser) {
        // Controllo della askMap
        for(Map.Entry<Integer, Bookvalue> entry : askMap.entrySet()) {
            Bookvalue bookValue = entry.getValue();
            Iterator<UserBook> iterator = bookValue.userList.iterator();
            while (iterator.hasNext()) {
                UserBook userBook = iterator.next();
                if (userBook.orderID == orderID && userBook.username.equals(onlineUser)) {
                    iterator.remove();
                    updateOrderBook();
                    if (bookValue.userList.isEmpty()) {
                        askMap.remove(entry.getKey());
                    }
                    System.out.println("Order " + orderID + " cancelled successfully.");
                    return 100;
                }
            }
        }

        //Controllo della bidMap
        for(Map.Entry<Integer, Bookvalue> entry : bidMap.entrySet()) {
            Bookvalue bookValue = entry.getValue();
            Iterator<UserBook> iterator = bookValue.userList.iterator();
            while (iterator.hasNext()) {
                UserBook userBook = iterator.next();
                if (userBook.orderID == orderID && userBook.username.equals(onlineUser)) {
                    iterator.remove();
                    updateOrderBook();
                    if (bookValue.userList.isEmpty()) {
                        bidMap.remove(entry.getKey());
                    }
                    System.out.println("Order " + orderID + " cancelled successfully.");
                    return 100;
                }
            }
        }
        
        //Controllo degli stopOrders
        Iterator<StopValue> iterator = stopOrders.iterator();
        while (iterator.hasNext()) {
            StopValue stopOrder = iterator.next(); // CORRETTO: rinominato da 'user' a 'stopOrder'
            if (stopOrder.orderID == orderID && stopOrder.username.equals(onlineUser)) {
                iterator.remove();
                updateOrderBook();
                System.out.println("Stop Order " + orderID + " cancelled successfully.");
                return 100;
            }
        }
        return 101; 
    }   
    
    //Metodo per aggiornare la size e i totali delle ask e bid map, lo spread ed elimina i prezzi la cui userList è vuota
    public void updateOrderBook(){
        //Controllo e rimozione delle askMap
        Iterator<Map.Entry<Integer, Bookvalue>> askIterator = askMap.entrySet().iterator();
        while (askIterator.hasNext()) {
            Map.Entry<Integer, Bookvalue> entry = askIterator.next();
            int price = entry.getKey();
            Bookvalue bookValue = entry.getValue();
            if (bookValue.userList.isEmpty()) {
                askIterator.remove();
            } else {
                int newSize = 0;
                for (UserBook user : bookValue.userList) {
                    newSize += user.size;
                }
                bookValue.size = newSize;
                bookValue.total = bookValue.size * price;
            }
        }    

        //Controllo e rimozione delle bidMap
        Iterator<Map.Entry<Integer, Bookvalue>> bidIterator = bidMap.entrySet().iterator();
        while (bidIterator.hasNext()) {
            Map.Entry<Integer, Bookvalue> entry = bidIterator.next();
            int price = entry.getKey();
            Bookvalue bookValue = entry.getValue();
            if (bookValue.userList.isEmpty()) {
                bidIterator.remove();
            } else {
                int newSize = 0;
                for (UserBook user : bookValue.userList) {
                    newSize += user.size;
                }
                bookValue.size = newSize;
                bookValue.total = bookValue.size * price;
            }
        }
        updateSpread();
    }

    //Metodo per aggiornare il valore dello spread
    public void updateSpread(){
        if (!bidMap.isEmpty() && !askMap.isEmpty()){
            int maxBid = bidMap.firstKey(); 
            int minAsk = askMap.firstKey(); 
            this.spread = minAsk - maxBid; // CORRETTO: spread = ask - bid (non bid - ask)
        } else if (bidMap.isEmpty() && !askMap.isEmpty()){
            this.spread = -1 * askMap.firstKey();
        } else if (!bidMap.isEmpty() && askMap.isEmpty()){
            this.spread = bidMap.firstKey();
        } else this.spread = 0;
    }

    //Metodo per incrementare il contatore degli ID degli ordini 
    public synchronized int updateLastOrderID() {
        lastOrderID++;
        return lastOrderID;
    }

    //Metodo che restituisce la size totale della askMap oppure della bidMap
    public int totalMapSize(ConcurrentSkipListMap<Integer,Bookvalue> map){
        int totalSize = 0;
        for (Map.Entry<Integer,Bookvalue> entry: map.entrySet()) {
            totalSize += entry.getValue().size;
        }
        return totalSize;
    }

    //Metodo che restituisce la size totale che un utente ha inserito nella askMap o bidMap
    public int totalUserSize(ConcurrentSkipListMap<Integer,Bookvalue> map, String username){
        int totalSize = 0;
        for (Map.Entry<Integer,Bookvalue> entry: map.entrySet()) {
            for (UserBook user : entry.getValue().userList) {
                if (user.username.equals(username)) {
                    totalSize += user.size; 
                }
            }
        }
        return totalSize;
    }

    // CORRETTO: Tipi di ritorno corretti con 'Bookvalue' invece di 'BookValue'
    public ConcurrentSkipListMap<Integer,Bookvalue> getAskMap(){
        return this.askMap;
    }

    public ConcurrentSkipListMap<Integer,Bookvalue> getBidMap(){
        return this.bidMap;
    }

    public int getSpread(){
        return this.spread;
    }

    public int getLastOrderID(){
        return lastOrderID;
    }

    public ConcurrentLinkedQueue<StopValue> getStopOrders(){
        return this.stopOrders;
    }

    public String toString(){
        return "OrderBook{" +
                "askMap=" + askMap +
                ", spread=" + spread +
                ", lastOrderID=" + lastOrderID +
                ", stopOrders=" + stopOrders +
                ", bidMap=" + bidMap +
                '}';
    }    
}