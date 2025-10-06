package OrderBook;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
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


    public void notifyUser(ConcurrentSkipListMap<String, SockMapValue> socketMap, String user, int orderID, String type, String orderType, int size, int price) {
        System.out.println("Notifica all'utente " + user);
        // Estrazione della porta dell'utente a cui mandare la notifica dalla socketMap
        int port = 0;
        InetAddress address = null;
        for(Map.Entry<String,SockMapValue> entry : socketMap.entrySet()){
            if(entry.getKey().equals(user)){
                port = entry.getValue().port;
                address = entry.getValue().address;
                break;
            }
        }
        if(port != 0 && address != null){
            try (DatagramSocket sock = new DatagramSocket()){
                Trade trade = new Trade(orderID, type, orderType, size, price);
                Gson gson = new Gson();
                String json = gson.toJson(trade);   
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
                sock.send(packet);
        /* Estrazione delle informazioni socket dell'utente
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
            */
            } catch (Exception e){
                System.err.println("NotifyUser() Errore: " + e.getMessage());
            }
        } else {
            System.out.println("Utente non online, messaggio non inviato.");
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

    public synchronized void checkStopOrders(ConcurrentSkipListMap<String,SockMapValue> socketMap){
        Iterator<StopValue> iterator = stopOrders.iterator();
        while(iterator.hasNext()){
            StopValue order = iterator.next();

            if(order.type.equals("ask")){ // Controllo della bidMap
                if(!bidMap.isEmpty()){
                    if(order.stopPrice <= bidMap.firstKey()){ //raggiunto stopPrice: esecuzione dell'ordine come un MarketOrder
                        // Si verifica che la lista della bidmap non contenga solo ordini inseriti dall'utente dello stopOrder
                        ConcurrentLinkedQueue<String> list = getUsers(bidMap.get(bidMap.firstKey()).userList);
                        if(list.stream().anyMatch(s -> !s.equals(order.username))){

                            int res = tryMarketOrder(order.type,order.size,order.username,"stop", socketMap);
                            lastOrderID--; // Si decrementa lastOrderID perchè si ha già un orderID per lo stopOrder
                            if(res != -1){// L'ordine è stato elaborato con successo
                                System.out.printf("%s's StopOrder processato con successo. Ordine: %s\n",order.username, order.toString());
                            } else{
                                System.out.printf("%s's StopOrder è stato elaborato ma ha fallito. Ordine: %s\n",order.username, order.toString());

                                notifyUser(socketMap, order.username, order.orderID, order.type, "stop", 0, 0);
                            }
                            iterator.remove();
                        }
                    }
                }
            } else{ // Controllo della askMap
                if(!askMap.isEmpty()){
                    if(order.stopPrice >= askMap.firstKey()){ //raggiunto stopPrice: esecuzione dell'ordine come un MarketOrder
                        // Si verifica che la lista della askmap non contenga solo ordini inseriti dall'utente dello stopOrder
                        ConcurrentLinkedQueue<String> list = getUsers(askMap.get(askMap.firstKey()).userList);
                        if(list.stream().anyMatch(s -> !s.equals(order.username))){

                            int res = tryMarketOrder(order.type,order.size,order.username,"stop", socketMap);
                            lastOrderID--; // Si decrementa lastOrderID perchè si ha già un orderID per lo stopOrder
                            if(res != -1){// L'ordine è stato elaborato con successo
                                System.out.printf("%s's StopOrder processato con successo. Ordine: %s\n",order.username, order.toString());
                            } else{
                                System.out.printf("%s's StopOrder è stato elaborato ma ha fallito. Ordine: %s\n",order.username, order.toString());

                                notifyUser(socketMap, order.username, order.orderID, order.type, "stop", 0, 0);
                            }
                            iterator.remove();
                        }
                    }
                }
            }
        }
    }

    /*Metodo per controllare tutti gli stopOrder per verificare se qualcuno di essi debba essere eseguito in base ai prezzi di mercato correnti
    public synchronized void checkStopOrders(ConcurrentSkipListMap<String, SockMapValue> socketMap) {
        Iterator<StopValue> iterator = stopOrders.iterator();

        while (iterator.hasNext()) {
            StopValue order = iterator.next();
            //boolean triggerCondition = false;
            //ConcurrentLinkedQueue<String> userList = null;

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
                    System.out.printf("%s's StopOrder elaborato con successo. Ordine: %s\n", order.username, order);
                } else {
                    System.out.printf("%s's StopOrder è stato elaborato, ma non è andato a buon fine. Ordine: %s\n", order.username, order);
                    notifyUser(socketMap, order.username, order.orderID, order.type, "stop", 0, 0);
                }

                iterator.remove();
                
            }
        }
    }
*/
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
              //  int matchedSize = Math.min(remainingSize, askValue.size); 
                remainingSize = tryMatch(remainingSize, user, "bid", askValue.userList, "ask", "limit", askPrice, orderID, socketMap);
            }
            if (remainingSize == 0){ //Ordine completato
                System.out.println("Numero d'ordine " + orderID + " completato.");
                updateOrderBook();
                return orderID; 
            }
        }
        if (remainingSize > 0) {
            loadBidOrder(remainingSize, price, user, orderID);
            if (remainingSize == size) {
                System.out.println("Numero d'ordine " + orderID + " non corrispondente: " + remainingSize + " aggiunto all'orderBook");
            } else {
                System.out.println("Numero d'ordine " + orderID + " è stato parzialmente completato; la dimensione rimanente di " + remainingSize + " è stata aggiunta all'orderBook");
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
           
    
    public synchronized int tryAskOrder(int size, int price, String user, ConcurrentSkipListMap<String, SockMapValue> socketMap) {
        int remainingSize = size;
        int orderID = updateLastOrderID();

        for (Map.Entry<Integer, Bookvalue> entry : bidMap.entrySet()) {
            int bidPrice = entry.getKey();
            Bookvalue bidValue = entry.getValue();

            if (bidPrice >= price) { // Se il prezzo di acquisto è superiore o uguale al prezzo di vendita
                remainingSize = tryMatch(remainingSize, user, "ask", bidValue.userList, "bid", "limit", bidPrice, orderID, socketMap);
            }    
                if(remainingSize == 0){ //Ordine completato
                    System.out.println("Numero d'ordine " + orderID + " completato.");
                    updateOrderBook();
                    return orderID; 
                }               
            }
            // L'ordine non è stato evaso completamente: deve essere caricato sull'orderBook
            if(remainingSize > 0){
                loadAskOrder(remainingSize, price, user, orderID);
                if(remainingSize == size){
                    // L'ordine non è stato matchato
                    System.out.println("Numero d'ordine "+ orderID + " non corrispondente: " + remainingSize + " aggiunto all'orderBook");
                } else{
                    // L'ordine è stato evaso parzialmente
                    System.out.println("Numero d'ordine "+ orderID + " è stato parzialmente completato; la dimensione rimanente di " + remainingSize + " è stata aggiunta all'orderBook");
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
                    iterator.remove();
                    notifyUser(socketMap, IUser.username, IUser.orderID, listType, "limit", IUser.size, price);
                    notifyUser(socketMap, user, orderID, userType, orderType, remainingSize, price);
                    
                    remainingSize = 0;
                }
            }
        }
        return remainingSize;
    }

public synchronized int tryMarketOrder(String type, int size, String user, String orderType ,ConcurrentSkipListMap<String,SockMapValue> socketMap){
        int remainingSize = size;
        
        if(type.equals("ask")){ // Ricevuto ask: bisogna matchare con bid
            
            // Si controlla se la mappa contiene abbastanza size per soddisfare l'ordine (escludendo la size degli ordini inseriti dall'utente che ha fatto il marketOrder)
            if(totalMapSize(bidMap)-totalUserSize(bidMap, user) < size){
                return -1;
            }

            int orderID = updateLastOrderID();
            
            for(Map.Entry<Integer, Bookvalue> entry : bidMap.entrySet()){ // Scorrimento della bidMap
                int price = entry.getKey();
                Bookvalue value = entry.getValue();
                
                remainingSize = tryMatch(remainingSize,user,"ask",value.userList,"bid",orderType,price,orderID,socketMap);

                if(remainingSize == 0){// Ordine completato
                    updateOrderBook();
                    return orderID; // Viene restituito il numero dell'ordine
                }
            }

        } else{ // Ricevuto bid: bisogna matchare con ask

            // Si controlla se la mappa contiene abbastanza size per soddisfare l'ordine (escludendo la size degli ordini inseriti dall'utente che ha fatto il marketOrder)
            if(totalMapSize(askMap)-totalUserSize(askMap, user) < size){
                return -1;
            }

            int orderID = updateLastOrderID();
            
            for(Map.Entry<Integer, Bookvalue> entry : askMap.entrySet()){ // Scorrimento della askMap
                int price = entry.getKey();
                Bookvalue value = entry.getValue();
                
                remainingSize = tryMatch(remainingSize,user,"bid",value.userList,"ask",orderType,price,orderID,socketMap);

                if(remainingSize == 0){// Ordine completato
                    updateOrderBook();
                    return orderID; // Viene restituito il numero dell'ordine
                }
            }
        }
        return -1;
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
                    System.out.println("Ordine " + orderID + " cancellato con successo.");
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
                    System.out.println("Ordine " + orderID + " cancellato con successo.");
                    return 100;
                }
            }
        }
        
        //Controllo degli stopOrders
        Iterator<StopValue> iterator = stopOrders.iterator();
        while (iterator.hasNext()) {
            StopValue stopOrder = iterator.next(); 
            if (stopOrder.orderID == orderID && stopOrder.username.equals(onlineUser)) {
                iterator.remove();
                updateOrderBook();
                System.out.println("Stop Order " + orderID + " cancellato con successo.");
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
            this.spread = maxBid - minAsk;
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