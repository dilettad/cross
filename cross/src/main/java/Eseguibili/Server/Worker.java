package Eseguibili.Server;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import Eseguibili.Main.MainServer;
import Gson.GsonAskHistory;
import Gson.GsonCredentials;
import Gson.GsonLimitStopOrder;
import Gson.GsonMarketOrder;
import Gson.GsonOrderBook;
import Gson.GsonResponse;
import Gson.GsonResponseOrder;
import Gson.GsonTrade;
import Gson.GsonUser;
import OrderBook.OrderBook;
import OrderBook.StopValue;

//Thread worker che gestisce la connessione e le richieste del client all'interno dell'orderbook
public class Worker implements Runnable {
    private Socket clientSocket; 
    private ConcurrentSkipListMap<String, SockMapValue> socketMap;
    public String hostname;
    public int UDPport;
    public int clientPort;
    public InetAddress clientAddress;

    public ConcurrentHashMap<String,Tupla> userMap;
    public OrderBook orderBook;
    public TimeoutHandler handler;
    //private SharedState sharedState; 

    private String username = null;
    private String password = null;
    private String onlineUser = null;
    private String type;
    private int size;
    private int price;

    //Oggetti JSON per la comunicazione
    public GsonResponse response = new GsonResponse();
    public GsonOrderBook responseOrderBook = new GsonOrderBook();
    public GsonResponseOrder responseOrder = new GsonResponseOrder(); 
    private static Gson gson = new Gson();

    //Flags usata per interrompere il while del worker in seguito alla chiusura del server
    public static AtomicBoolean running = new AtomicBoolean(true);

    public Worker(Socket socket, ConcurrentHashMap<String,Tupla> userMap, OrderBook orderBook, ConcurrentSkipListMap<String,SockMapValue> socketMap, int UDPport){
        this.clientSocket = socket;
        this.userMap = userMap;
        this.orderBook = orderBook;
        this.socketMap = socketMap;
        this.UDPport = UDPport;
        updateJsonOrderBook(orderBook);
    }
    
    //Classe per condividere dati tra worker e timeoutHandler
    public class SharedState{
        public AtomicBoolean activeUser = new AtomicBoolean(true);          //Flags per tenere traccia dello stato dell'utente (attivo o no)
        public AtomicBoolean runningHandler = new AtomicBoolean(true);      //Flags usata per interrompere l'esecuzione dell'handler
        public volatile long lastActivity = System.currentTimeMillis(); 
        public volatile ConcurrentLinkedQueue<StopValue> stopOrders = new ConcurrentLinkedQueue<>();
    }
    
    
    public void run() {
        System.out.printf("[WORKER %s] Connessione attiva con il client", Thread.currentThread().getName());
        SharedState sharedState = new SharedState();

        //Creazione del thread che gestisce il timeout
        handler = new TimeoutHandler(sharedState);
        Thread timeout = new Thread(handler);
        timeout.start();

        //Sincro degli stopOrder dell'handler con quelli dell'orderbook
        handler.syncWithOrderBook(orderBook);
        DatagramSocket UDPsocket = null;

        //Apertura comunicazione UDP per ricevere porta e IP del client
        
        try{
            //LOG PER SCOPRIRE BUG
            System.out.printf("[WORKER %s] TENTATIVO APERTURA UDP PORT %d\n", Thread.currentThread().getName(), UDPport);
            UDPsocket = new DatagramSocket(null);
            //UDPsocket.setReuseAddress(true);
            UDPsocket.setSoTimeout(5000);
            UDPsocket.bind(new InetSocketAddress(UDPport));

            System.out.printf("[WORKER %s] Socket UDP in ascolto sulla porta %d\n", Thread.currentThread().getName(), UDPport);
            //DatagramSocket UDPsocket = new DatagramSocket(UDPport)){
            clientSocket.setSoTimeout(5000); 
            try(BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream())); PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {
                response.setResponse("UDP", UDPport, "");
                response.sendMessage(gson, out);
                System.out.printf("[WORKER %s] AVVIO dal while principale\n", Thread.currentThread().getName());
                while(sharedState.activeUser.get() && running.get()){
                    // Try-catch per catturare l'eccezione del timeout del socket
                    try{
                        // Attesa messaggio dal client
                        String line = in.readLine();
                        // Si setta il timestamp per il controllo del timeout
                        long time = System.currentTimeMillis();
                        handler.setTimestamp(time);
                        // Sincronizzazione degli stopOrder dell'handler con quelli dell'orderBook   
                        handler.syncWithOrderBook(orderBook);           

                        // Lettura dell'oggetto e parsing ad un JsonObject
                        JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                        
                        // Estrazione dell'operazione
                        String operation = obj.get("operation").getAsString();
        
                        JsonObject valuesObj;

                        // Creazione del tipo di valore appropriato in base all'operazione
                        switch(operation){
                            case "register":
                                // Per la registrazione i valori sono di tipo GsonUser
                                valuesObj = obj.getAsJsonObject("values");
                                GsonUser valuesR = new Gson().fromJson(valuesObj, GsonUser.class);

                                // Estrazione dei valori da values
                                username = valuesR.getUsername();
                                password = valuesR.getPassword();
                                try{
                                    if(onlineUser != null){
                                        // Utente è loggato
                                        response.setResponse("register",103," Non puoi registrarti, sei loggato con l'username " + onlineUser);
                                        response.sendMessage(gson,out);
                                    } else if(!isValid(password)){
                                        // Password non valida
                                        response.setResponse("register",101,"Password non valida");
                                        response.sendMessage(gson,out);
                                    } else if((userMap.putIfAbsent(username,new Tupla(password,false))) == null){

                                        // Modifica del file userMap.json
                                        updateJsonUsermap(userMap);
                                        
                                        // Comunicazione al client: registrazione completata
                                        response.setResponse("register",100,"OK");
                                        response.sendMessage(gson,out);
                                        
                                    } else{
                                        // Username già esistente
                                        response.setResponse("register",102,"Username non disponibile");
                                        response.sendMessage(gson,out);
                                    }
                                }catch (Exception e){
                                    response.setResponse("register",103,e.getMessage());
                                    response.sendMessage(gson,out);
                                }
                                
                                username = null;
                                
                            break;

                            case "updateCredentials":
                                // Per updateCredentials i valori sono di tipo GsonCredentials
                                valuesObj = obj.getAsJsonObject("values");
                                GsonCredentials valuesC = new Gson().fromJson(valuesObj, GsonCredentials.class);

                                // Estrazione dei valori da values
                                username = valuesC.getUsername();
                                String oldPassword = valuesC.getOldPassword();
                                String newPassword = valuesC.getNewPassword();
                                try{
                                    if(onlineUser != null){
                                        // L'utente è loggato
                                        response.setResponse("updateCredentials",104,"Impossibile aggiornare: utente attualmente connesso");
                                        response.sendMessage(gson,out);

                                    } else if(userMap.containsKey(username)){
                                        // L'username esiste
                                        if((userMap.get(username)).getPassword().equals(oldPassword)){
                                            // Password vecchia corretta
                                            if(oldPassword.equals(newPassword)){
                                                // La nuova e la vecchia password sono uguali
                                                response.setResponse("updateCredentials",103,"Impossibile aggiornare: la nuova password è uguale a quella vecchia");
                                                response.sendMessage(gson,out);
                                            } else if(isValid(newPassword)){
                                                // Nuova password è valida

                                                // Modifica della userMap
                                                userMap.replace(username, new Tupla(newPassword, false));
                                            
                                                // Modifica del file userMap.json
                                                updateJsonUsermap(userMap);

                                                // Comunicazione al client
                                                response.setResponse("updateCredentials",100,"OK");
                                                response.sendMessage(gson,out);
                                            } else{
                                                response.setResponse("updateCredentials",101,"Impossibile aggiornare: nuova password non valida"); 
                                                response.sendMessage(gson,out);
                                            }

                                        } else {
                                            response.setResponse("updateCredentials",102,"Impossibile aggiornare: password vecchia errata");
                                            response.sendMessage(gson,out);
                                        }
                                    } else {
                                        response.setResponse("updateCredentials",102,"Impossibile aggiornare: username non trovato");
                                        response.sendMessage(gson,out);
                                    }
                                } catch (Exception e){
                                    response.setResponse("updateCredentials",105,e.getMessage());
                                    response.sendMessage(gson,out);
                                }
                            break;                     

                            case "login":
                                // Per login i valori sono di tipo GsonUser
                                valuesObj = obj.getAsJsonObject("values");
                                GsonUser valuesLI = new Gson().fromJson(valuesObj, GsonUser.class);

                                // Estrazione dei valori da values
                                username = valuesLI.getUsername();
                                password = valuesLI.getPassword();
                                
                                // Procedura di login
                                try{
                                    if(userMap.containsKey(username)){
                                        // L'username esiste
                                        if((userMap.get(username)).getPassword().equals(password)){
                                            // Password corretta
                                            if((userMap.get(username)).getLogged()){
                                                // L'utente è già loggato
                                                response.setResponse("login",102,"Utente già loggato");
                                                response.sendMessage(gson,out);
                                            } else{
                                                // L'utente non è loggato
                                                if(onlineUser == null){
                                                    // Il client non ha già fatto il login con un altro account

                                                    // Memorizzazione dell'utente loggato
                                                    onlineUser = username;

                                                    // Passaggio dell'username al thread Handler del timeout
                                                    handler.setUsername(onlineUser);

                                                    // Modifica della userMap
                                                    userMap.replace(username, new Tupla(password, true));

                                                    // Modifica del file userMap.json
                                                    updateJsonUsermap(userMap);

                                                    // Comunicazione al client
                                                    response.setResponse("login",100,"OK");
                                                    response.sendMessage(gson,out);

                                                    // Si attende il pacchetto UDP dal client per estrarre porta e indirizzo
                                                    byte[] buffer = new byte[1];
                                                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                                                    UDPsocket.receive(packet);

                                                    // Estrazione indirizzo e porta del cliente
                                                    clientPort = packet.getPort();
                                                    clientAddress = packet.getAddress();

                                                    // Aggiunta di indirizzo e porta alla socketMap
                                                    SockMapValue newValue = new SockMapValue(clientPort, clientAddress);
                                                    if(socketMap.containsKey(onlineUser))
                                                        socketMap.replace(onlineUser, newValue);
                                                    else
                                                        socketMap.put(onlineUser,newValue);

                                                } else {
                                                    response.setResponse("login",103,"Sei già connesso con un altro account con username " + onlineUser);
                                                    response.sendMessage(gson,out);
                                                }
                                            }
                                        } else{ // Password errata
                                            response.setResponse("login",101,"Password errata");
                                            response.sendMessage(gson,out);
                                        }
                                    } else{ // Username non trovato
                                        response.setResponse("login",101,"Username non trovato");
                                        response.sendMessage(gson,out);
                                    }
                                }catch (Exception e){
                                    response.setResponse("login",103,e.getMessage());
                                    response.sendMessage(gson,out);
                                }
                            break;

                            case "logout":
                                // Per logout values non avrà nessun valore
                                valuesObj = obj.getAsJsonObject("values");
                                
                                try{
                                    if(onlineUser == null){
                                        System.out.println("Utente non connesso ha richiesto il logout");
                                        response.setResponse("logout",101,"Chiusura connessione...");
                                        response.sendMessage(gson,out);
                                    } else {
                                        // L'utente è loggato
                                        System.out.println(onlineUser + " ha richiesto il logout");

                                        // Modifica della userMap
                                        userMap.replace(onlineUser, new Tupla(password, false));

                                        // Modifica del file userMap.json
                                        updateJsonUsermap(userMap);

                                        // Comunicazione al client
                                        response.setResponse("logout",100,"OK");
                                        response.sendMessage(gson,out);
                                    } 
                                    // Terminazione del thread handler del timeout
                                    sharedState.runningHandler.set(false);
                                    timeout.join(); // Si attende la terminazione del TimeoutHandler

                                    // Si rimuove il worker dalla lista di MainServer
                                    MainServer.workerList.remove(this);

                                    // Chiusura comunicazione
                                    clientSocket.close();
                                    return;

                                } catch (Exception e){
                                    response.setResponse("logout",103,e.getMessage());
                                    response.sendMessage(gson,out);
                                }
                            break;

                            case "insertLimitOrder":
                                System.out.println(onlineUser + " ha inserito un ordine limite");
                                try{
                                    // Per limitOrder i valori sono di tipo GsonLimitStopOrder
                                    valuesObj = obj.getAsJsonObject("values");
                                    GsonLimitStopOrder valuesL = new Gson().fromJson(valuesObj, GsonLimitStopOrder.class);

                                    // Estrazione dei valori da values
                                    type = valuesL.getType();
                                    size = valuesL.getSize();
                                    price = valuesL.getPrice();

                                    // L'utente è già loggato
                                    int orderID;
                                    if(type.equals("ask")){
                                        // ORDINE DI ASK: VENDITA
                                        orderID = orderBook.tryAskOrder(size,price,onlineUser,socketMap);
                                    } else{
                                        // ORDINE DI BID: ACQUISTO
                                        orderID = orderBook.tryBidOrder(size, price, onlineUser, socketMap);
                                    }
                                    // Controllo della lista degli StopOrder
                                    orderBook.checkStopOrders(socketMap);

                                    // Aggiornamento della lista degli stopOrder del TimeoutHandler
                                    handler.updateStopOrders(orderBook.stopOrders);

                                    // Aggiornamento del file orderBook.json
                                    updateJsonOrderBook(orderBook);

                                    // Incio del messaggio al client con l'orderID
                                    responseOrder.setResponseOrder(orderID);
                                    responseOrder.sendMessage(gson,out);
                                } catch (Exception e){
                                    System.out.println("[WORKER] Errore in LimitOrder: " + e.getMessage());

                                    // Invio del messaggio al client con il codice di errore
                                    responseOrder.setResponseOrder(-1);
                                    responseOrder.sendMessage(gson,out);
                                }
                            break;

                            case "insertMarketOrder":
                                System.out.println(onlineUser + " ha inserito un ordine di mercato");

                                // Per marketOrder i valori sono di tipo GsonMarketOrder
                                valuesObj = obj.getAsJsonObject("values");
                                GsonMarketOrder valuesM = new Gson().fromJson(valuesObj, GsonMarketOrder.class);

                                // Estrazione dei valori da values
                                type = valuesM.getType();
                                size = valuesM.getSize();

                                // Esecuzione del market order
                                int res = orderBook.tryMarketOrder(type,size,onlineUser,"market",socketMap);

                                // Controllo della lista degli StopOrder
                                orderBook.checkStopOrders(socketMap);

                                // Aggiornamento della lista degli stopOrder del TimeoutHandler
                                handler.updateStopOrders(orderBook.stopOrders);

                                // Aggiornamento del file orderBook.json
                                updateJsonOrderBook(orderBook);

                                // Invio del messaggio al client con l'orderID oppure il codice di errore
                                responseOrder.setResponseOrder(res);
                                responseOrder.sendMessage(gson,out);
                            break;
                            
                            case "insertStopOrder":
                                System.out.println(onlineUser + " ha inserito un ordine di stop");

                                // Per stopOrder i valori sono di tipo GsonLimitStopOrder
                                valuesObj = obj.getAsJsonObject("values");
                                GsonLimitStopOrder valuesS = new Gson().fromJson(valuesObj, GsonLimitStopOrder.class);

                                // Estrazione dei valori da values
                                type = valuesS.getType();
                                size = valuesS.getSize();
                                price = valuesS.getPrice();

                                // Aggiunta dell'ordine nella lista degli stop orders
                                int orderID = orderBook.updateLastOrderID();
                                orderBook.stopOrders.add(new StopValue(type,size,onlineUser,orderID,price));

                                // Si controlla se ci sono ordini da eseguire
                                //orderBook.checkStopOrders(socketMap);
                                                                
                                // Aggiornamento della lista degli stopOrder del TimeoutHandler
                                handler.updateStopOrders(orderBook.stopOrders);

                                // Aggiornamento del file orderBook.json
                                updateJsonOrderBook(orderBook);

                                // Invio del messaggio al client con l'orderID oppure il codice di errore
                                responseOrder.setResponseOrder(orderID);
                                responseOrder.sendMessage(gson,out);
                            break;

                            case "cancelOrder":
                                try{
                                    // Per cancelOrder i valori sono di tipo GsonResposeOrder
                                    valuesObj = obj.getAsJsonObject("values");
                                    GsonResponseOrder valuesCO = new Gson().fromJson(valuesObj, GsonResponseOrder.class);

                                    // Estrazione dei valori da values
                                    orderID = valuesCO.getOrderID();

                                    res = orderBook.cancelOrder(orderID, onlineUser);

                                    // Controllo della lista degli StopOrder
                                    orderBook.checkStopOrders(socketMap);

                                    // Aggiornamento della lista degli stopOrder del TimeoutHandler
                                    handler.updateStopOrders(orderBook.stopOrders);

                                    // Modifica del file userBook.json
                                    updateJsonOrderBook(orderBook);

                                    String message = "";
                                    if(res==100)
                                        message = "OK";

                                    // Comunicazione al client
                                    response.setResponse("cancelOrder",res,message);
                                    response.sendMessage(gson,out);

                                } catch (Exception e){
                                    System.err.println("[WORKER] Errore in cancelOrder: " + e.getMessage() + e.getCause());
                                }
                            break;

                            case "showOrderBook":
                                // Per showOrderBook values non avrà nessun valore
                                valuesObj = obj.getAsJsonObject("values");

                                // Setting dell'orderBook e invio al client
                                responseOrderBook.setOrderBook("showOrderBook",orderBook);
                                responseOrderBook.sendMessage(gson,out);
                            break;

                            case "showStopOrders":
                                // Per showStopOrders values non avrà nessun valore
                                valuesObj = obj.getAsJsonObject("values");
                                
                                // Setting degli stopOrders e invio al client
                                responseOrderBook.setOrderBook("showStopOrders",orderBook);
                                responseOrderBook.sendMessage(gson,out);
                            break;

                            case "getPriceHistory":
                                try{
                                    // Per getPriceHistory i valori sono di tipo GsonHistory
                                    valuesObj = obj.getAsJsonObject("values");
                                    GsonAskHistory valuesH = new Gson().fromJson(valuesObj, GsonAskHistory.class);
                                    
                                    // Estrazione dei valori da values
                                    String date = valuesH.getDate();
                                    System.out.println(onlineUser + " ha richiesto la cronologia dei prezzi da " + date);

                                    String tradeInfo = readHistory(date);

                                    // Comunicazione al client
                                    response.setResponse("getPriceHistory",0,tradeInfo);
                                    response.sendMessage(gson,out);

                                } catch(Exception e){
                                    System.err.println("[WORKER] getPriceHistory: " + e.getMessage() + e.getCause());
                                }
                            break;

                            default:
                                System.out.println("[Worker] Comando ricevuto non trovato");
                        } // Fine switch
                    } catch (SocketTimeoutException e){
                        // readLine() è scaduto, si verifica se il TimeoutHandler ha segnalato un timeout
                        if(!sharedState.activeUser.get()){
                            break;
                        }
                        // Altrimenti si continua il ciclo
                        continue;
                    }
                }
                // Procedura di terminazione del worker

                System.out.printf("[WORKER %s] Uscito dal while principale\n", Thread.currentThread().getName());
                // Terminazione del thread handler
                sharedState.runningHandler.set(false);
                System.out.printf("[WORKER %s] Attendendo terminazione timeout handler...\n", Thread.currentThread().getName());
                timeout.join(); // Si attende la terminazione del TimeoutHandler
                System.out.printf("[WORKER %s] Timeout handler terminato\n", Thread.currentThread().getName());
              
              
                String closingMessage = "";
                if(!sharedState.activeUser.get()) // Inattività del client
                    closingMessage = "Chiusura della connessione a causa del timeout di inattività.";
            
                if(!running.get()){ // Shutdown del server
                    closingMessage = "Chiusura della connessione a causa dello shutdown del server.";
                    //response.setResponse("disconnection",100,closingMessage);
                    //response.sendMessage(gson,out);
                }
              
                if(onlineUser == null){
                    System.out.println("Disconnessione dell'utente non loggato");
                    response.setResponse("disconnection",100,closingMessage);
                    response.sendMessage(gson,out);
                } else {
                    // L'utente è loggato
                    System.out.println("Disconnessione di " + onlineUser);

                    // Modifica della userMap
                    userMap.replace(onlineUser, new Tupla(password, false));

                    // Modifica del file userMap.json
                    updateJsonUsermap(userMap);

                    // Comunicazione al client
                    response.setResponse("disconnection",100,closingMessage);
                    response.sendMessage(gson,out);
                }
                // Si rimuove il worker dalla lista di MainServer
                MainServer.workerList.remove(this);

                // Chiusura comunicazione
                clientSocket.close();
                System.out.printf("[WORKER %s] Chiusura completata\n", Thread.currentThread().getName());
                //return;

            } catch (Exception e){ 
            System.err.println("[WORKER] Errore TCP: " + e.getMessage() + " - Causa: " + e.getCause());
            }
        } catch (IOException e) { 
            System.err.println("[Worker] Errore UDP sulla porta:  " + UDPport + " - " + e.getMessage());
        
        } finally{
            System.out.printf("[WORKER %s] Entrando in finally\n", Thread.currentThread().getName());
                
                if (UDPsocket != null && !UDPsocket.isClosed()) {
                    System.out.printf("[WORKER %s] Chiudendo socket UDP porta %d\n", 
                                    Thread.currentThread().getName(), UDPport);
                    UDPsocket.close();
                    System.out.printf("[WORKER %s] Socket UDP chiuso\n", Thread.currentThread().getName());
                }
                
            MainServer.workerList.remove(this);
            System.out.printf("[WORKER %s] Finally completato\n", Thread.currentThread().getName());
        }

    }
        
  
    /*Metodo per chiudere il worker
    public void shutdown(){
        running.set(false);
    }*/

    //Metodo per chiudere il worker
    public void shutdown(){
        running.set(false);
        try {
            if (clientSocket != null && !clientSocket.isClosed()) {
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                response.setResponse("disconnection",100,"Chiusura della connessione a causa dello shutdown del server.");
                response.sendMessage(gson,out);
                clientSocket.shutdownInput();
                clientSocket.shutdownOutput();
                clientSocket.close(); //Chiusura forzata 
                
            }
        } catch (IOException e) {
            System.err.println("[WORKER] Errore nella chiusura del socket: " + e.getMessage());
        }   
    }

    //Metodo per leggere i dati giornalieri dello storico solo per mese e anno
    public String readDailyData(String date) { 
        // Implementazione per leggere i dati giornalieri dello storico
        String monthPassed = date.substring(0,2); 
        String yearPassed = date.substring(2);

        StringBuilder keyBuilder = new StringBuilder();

        //Mappa per memorizzare i dati di ogni giorno
        ConcurrentSkipListMap<String, DailyParameters> daysMap = new ConcurrentSkipListMap<>();
        try (JsonReader reader = new JsonReader(new FileReader("src/Json/dailyData.json"))) {
            Gson gson = new Gson();
            
            //Formato per estrarre solo la data senza ora
            SimpleDateFormat dayFormat = new SimpleDateFormat("dd/MM/yyyy");
            SimpleDateFormat monthFormat = new SimpleDateFormat("MM");
            SimpleDateFormat yearFormat = new SimpleDateFormat("yyyy");
            reader.beginObject();

            while(reader.hasNext()){
                String key = reader.nextName();
                //Se key è "trades"
                if (key.equals("trades")) {
                    //Leggo gli oggetti trade
                    reader.beginArray();
                    while (reader.hasNext()){
                        //Deserializzazione di trade come oggetto GsonTrade
                        GsonTrade trade = gson.fromJson(reader, GsonTrade.class);
                        //Creo oggetto date e estraggo mese e anno
                        Date tradeDate = new Date(trade.getTime()*1000);
                        String month = monthFormat.format(tradeDate);
                        String year = yearFormat.format(tradeDate);
                        //Filtro ordine per mese e anno
                        if (month.equals(monthPassed) && year.equals(yearPassed)) {
                            //Timestamp in data leggibile e aggiorno i dati giornalieri
                            String readableDate = dayFormat.format(tradeDate);
                            // Aggiornamento dei dati giornalieri 
                            if (!daysMap.containsKey(readableDate)) {
                                //Primo trade del giorno - CORRETTO: aggiunto parametri mancanti
                                daysMap.put(readableDate, new DailyParameters(readableDate, trade.getPrice(), trade.getTime()));
                            } else {
                                //Aggiornamento dei dati esistenti
                                daysMap.get(readableDate).updatePrices(trade.getPrice(), trade.getTime());
                            }
                        }
                    }
                    reader.endArray();
                } else {
                    //Se campi presenti salto
                    reader.skipValue();
                }
            }
            reader.endObject();
            //Creazione della stringa risultato contenente i dati giornalieri
            keyBuilder.append("Daily Data\n");

            for (Map.Entry<String, DailyParameters> entry: daysMap.entrySet()) {
                DailyParameters params = entry.getValue();
                keyBuilder.append(String.format("Date: %s, OpenPrice: %d, MaxPrice: %d, MinPrice: %d, ClosePrice: %d\n", 
                entry.getKey(), params.openPrice, params.highPrice, 
                params.lowPrice, params.closePrice));
            }
        } catch (Exception e) {
            System.err.printf("[WORKER] Errore: %s \n", e.getMessage());
        }
        return keyBuilder.toString();
    }

    //Metodo per sincronizzare l'orderbook
    public void updateTimeoutHandler(){
      if (this.handler != null) {
          this.handler.syncWithOrderBook(orderBook);
      }
    }

        // Metodo per leggere i dati giornalieri dallo storico
    public String readHistory(String date){
        String monthPassed = date.substring(0, 2);
        String yearPassed = date.substring(2);

        StringBuilder result = new StringBuilder();

        // Definizione di una mappa per memorizzare i dati di ogni giorno
        ConcurrentSkipListMap<String,DailyParameters> daysMap = new ConcurrentSkipListMap<>();

        try(JsonReader reader = new JsonReader(new FileReader("src/Json/storicoOrdini.json"))) {
            Gson gson = new Gson();

            // Formato per estrarre solo la data senza ora
            SimpleDateFormat dayFormat = new SimpleDateFormat("dd/MM/yyyy");

            // Formato per estrarre solo il mese
            SimpleDateFormat monthFormat = new SimpleDateFormat("MM");
            // Formato per estrarre solo l'anno
            SimpleDateFormat yearFormat = new SimpleDateFormat("yyyy");
            
            // Lettura dell'oggetto JSON principale
            reader.beginObject();
            
            while(reader.hasNext()){
                String name = reader.nextName();
                
                if(name.equals("trades")){
                    // Lettura dell'array "trades"
                    reader.beginArray();
                    
                    while(reader.hasNext()){
                        // Deserializzazione di ciascun trade come un oggetto GsonTrade
                        GsonTrade trade = gson.fromJson(reader, GsonTrade.class);

                        // Creazione di un oggetto Date dal timestamp
                        Date tradeDate = new Date(trade.getTime() * 1000L);
                        
                        // Estrazione del mese
                        String month = monthFormat.format(tradeDate);
                        
                        // Estrazione dell'anno
                        String year = yearFormat.format(tradeDate);
                        
                        // Filtraggio degli ordini del mese e anno desiderato
                        if(month.equals(monthPassed) && year.equals(yearPassed)){

                            // Conversione del timestamp in formato data leggibile
                            String dayKey = dayFormat.format(tradeDate);
                            
                            // Aggiornamento dei dati giornalieri
                            if(!daysMap.containsKey(dayKey)){
                                // Primo trade del giorno
                                daysMap.put(dayKey, new DailyParameters(dayKey, trade.getPrice(), trade.getTime()));
                            } else {
                                // Aggiornamento dei dati esistenti
                                daysMap.get(dayKey).updatePrices(trade.getPrice(), trade.getTime());
                            }
                        }
                    }
                    reader.endArray();
                } else {
                    // Salta altri campi se presenti
                    reader.skipValue();
                }
            }
            reader.endObject();

            // Creazione della stringa risultato contenente i dati giornalieri
            result.append("\n=== DATI GIORNALIERI ===\n");
            for(Map.Entry<String,DailyParameters> entry : daysMap.entrySet()){
                DailyParameters param = entry.getValue();
                result.append(String.format("Date: %s, OpenPrice: %d, MaxPrice: %d, MinPrice: %d, ClosePrice: %d\n", 
                entry.getKey(), param.openPrice, param.highPrice, 
                param.lowPrice, param.closePrice));
            }
        } catch (Exception e){
            System.err.println("[WORKER] Errore: " + e.getMessage() + " - Causa: " + e.getCause());
        }
        return result.toString();
    }
    
    //Metodo per verificare se una stringa passata è valida
    public static boolean isValid(String string){
        if (string == null || string.isEmpty()) {
            return false;
        }
        //Ammessi caratteri alfanumerici
        return string.matches("^[a-zA-Z0-9]+$");
    }

    // Metodo per modificare il file JSON che mostra la userMap
    public static void updateJsonUsermap(ConcurrentHashMap<String,Tupla> userMap){
        try(BufferedWriter writer = new BufferedWriter(new FileWriter("src/Json/userMap.json"))){
            Gson g = new GsonBuilder().setPrettyPrinting().create();
            writer.write(g.toJson(userMap));

        } catch (Exception e){
            System.err.printf("[WORKER] updateJsonUsermap %s \n",e.getMessage());
        }
    }

    //Metodo per modificare il file JSON che mostra l'orderBook
    public static void updateJsonOrderBook(OrderBook orderBook) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter("src/Json/orderBook.json"))) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            writer.write(gson.toJson(orderBook));
            //Sincro tutti i worker quando l'orderbook cambia
            for (Worker worker : MainServer.workerList) { 
                if (worker != null && worker.handler != null){
                    worker.handler.syncWithOrderBook(orderBook);
                }
            }
        } catch (Exception e) {
            System.err.printf("[WORKER] updateJsonOrderBook %s \n", e.getMessage());
        }
    }
}