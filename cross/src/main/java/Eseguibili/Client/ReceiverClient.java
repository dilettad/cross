package Eseguibili.Client;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import Eseguibili.Main.MainClient.SharedData;

// Thread che si occupa di ricevere messaggi dal server e processarli
public class ReceiverClient implements Runnable{
    public Socket serverSock;           //Socket connesso al server
    public BufferedReader in;           //Reader per ricevere i dati dal server
    public Printer printer;             //Gestore per la stampa asincrona dei messaggi
    public SharedData sharedData;       //Oggetto contenente i dati condivisi tra i thread

    public ReceiverClient(Socket servSock,BufferedReader in, Printer printer, SharedData sharedData){
        this.serverSock = servSock;
        this.in = in;
        this.printer = printer;
        this.sharedData = sharedData;
    }

    public void run(){
        try{
            String line;
            // Ciclo di lettura dei messaggi
            while(!Thread.currentThread().isInterrupted() && !serverSock.isClosed() && !sharedData.isShuttingDown.get() && (line = in.readLine()) != null){
                try {
                    // Si converte la stringa ricevuta in un oggetto JSON 
                    JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                    // Elaborazione del messaggio
                    processMessage(obj);
                    
                }catch (JsonSyntaxException e) { // Gestione delle eccezioni per la sintassi JSON
                    printer.print("Errore parsing JSON: " + e.getMessage());
                }catch (Exception e) {
                    printer.print("Errore elaborazione messaggio: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted() && !sharedData.isShuttingDown.get()) {
                printer.print("Errore IO: " + e.getMessage());
            }
        } catch (Exception e) {
            printer.print("Errore inaspettato: " + e.getMessage());
        }finally {
            sharedData.isClosed.set(true); // Stato di chiusura
            sharedData.isShuttingDown.set(true); // Notifica al main di chiudere
        }
    }

    // Metodo per smistare i messaggi Json
    private void processMessage(JsonObject obj) {
        if(obj.get("type") != null && (obj.get("orderBook")!=null || obj.get("stopOrders")!=null)){ 
            // Ricevuto messaggio di tipo GsonOrderBook o GsonStopOrders
            String type = obj.get("type").getAsString();

            if(type.equals("showOrderBook")){
                showOrderBook(obj); // Stampa l'order book
            }
            if(type.equals("showStopOrders")){
                showStopOrders(obj); // Stampa gli stop orders
            }
            printer.promptUser();
            
        } else if((obj.get("type")) != null){ 
            // Ricevuto un messaggio di tipo GsonResponse (Es. login, logout, register, ecc.)
            handleGsonResponse(obj);
            
        } else if((obj.get("orderID")) != null){ 
            // Ricevuto un messaggio di tipo GsonResponseOrder (Es. newOrder, newStopOrder)
            handleOrderResponse(obj);
        }
    }

    private void handleGsonResponse(JsonObject obj) {
        String type = obj.get("type").getAsString();
        String errorMessage = obj.get("errorMessage") != null ? 
                              obj.get("errorMessage").getAsString() : "Unknown error";
        
        switch(type){
            //Caso di registrazione
            case "register":
                if(errorMessage.equals("OK"))
                    printer.print("Registrazione completata con successo");
                else
                    printer.print(errorMessage);
                break;
            //Caso di login 
            case "login":
                if(errorMessage.equals("OK")){
                    sharedData.isLogged.set(true);
                    sharedData.loginError.set(false);
                    printer.print("Login avvenuto con successo. Benvenuto");
                } else{
                    sharedData.loginError.set(true);
                    printer.print( errorMessage);   
                }
                break;
                
            //Caso di aggiornamento credenziali
            case "updateCredentials":
                if(errorMessage.equals("OK"))
                    printer.print("Le tue credenziali sono state aggiornate con successo.");
                else
                    printer.print(errorMessage);
                break;
            //Caso di logout
            case "logout":
                if(errorMessage.equals("OK"))
                    System.out.println("Logout completato. Grazie per aver utilizzato il nostro servizio");
                else
                    System.out.println(errorMessage);
                try {
                    serverSock.close();
                } catch (IOException e) {
                    System.out.println("Errore chiusura socket: " + e.getMessage());
                }
                sharedData.isClosed.set(true);
                return;
            //Caso di cancellazione ordine
            case "cancelOrder":
                if(errorMessage.equals("OK"))
                    printer.print("Cancellazione completata per questo ordine");
                else
                    printer.print("Cancellazione non disponibile per questo ordine");
                break;

            // Caso per visualizzare la cronologia dei prezzi
            case "getPriceHistory":
                printer.print(errorMessage);
                break;

            case "UDP": // porta UDP comunicata dal server
                if(obj.get("response") != null) {
                    int response = obj.get("response").getAsInt();
                    sharedData.UDPport = response;
                }
                break;

            // Caso di disconnessione: server forza la chiusura
            case "disconnection":
                System.out.println(errorMessage);
                sharedData.isClosed.set(true);
                sharedData.isShuttingDown.set(true);
                try {
                    if (serverSock != null && !serverSock.isClosed()) {
                        serverSock.close();
                    }
                } catch (IOException e) {
                    System.out.println("Errore chiusura socket: " + e.getMessage());
                }
                System.exit(0); 
                break;
                          
            default:
                printer.print("Unknown message type: " + type);
                break;
        }
        printer.promptUser(); //Mostrato il prompt
    }

    //Gestione dei messaggi relativi ad un ordine specifico
    private void handleOrderResponse(JsonObject obj) {
        int orderID = obj.get("orderID").getAsInt(); //Estrae l'ID dell'ordine
        if(orderID != -1)
            printer.print("Il tuo ordine ID è: " + orderID);
        else
            printer.print("Ops! Qualcosa è andato storto");
        printer.promptUser();
    }

    // Metodo per mostrare l'orderBook al client in modo formattato
    public void showOrderBook(JsonObject obj){
        // Estrazione oggetto dall'orderBook
        JsonObject orderBookObj = obj.getAsJsonObject("orderBook");
        
        // Formattazione dell'output
        StringBuilder output = new StringBuilder();
        output.append("\n==================== ORDER BOOK ===================\n");
        output.append("+------------------------+------------------------+\n");
        output.append("|       ASK (sellers)    |       BID (buyers)     |\n");
        output.append("+------------------------+------------------------+\n");
        output.append("|  Price   |  Quantity   |  Price   |  Quantity   |\n");
        output.append("+----------+-------------+----------+-------------+\n");
        
        // Estrazione delle mappe
        JsonObject askMap = null;
        if(orderBookObj.has("askMap") && orderBookObj.get("askMap").isJsonObject())
            askMap = orderBookObj.getAsJsonObject("askMap");
        JsonObject bidMap = null;
        if(orderBookObj.has("bidMap") && orderBookObj.get("bidMap").isJsonObject())
            bidMap = orderBookObj.getAsJsonObject("bidMap");

        // Estrazione dello spread
        int spread = 0;
        if(orderBookObj.has("spread") && orderBookObj.get("spread").isJsonPrimitive()) {
            spread = orderBookObj.get("spread").getAsInt();
        }
        
        // Calcolo del numero di righe necessarie
        int askRows = (askMap != null) ? askMap.size() : 0;
        int bidRows = (bidMap != null) ? bidMap.size() : 0;
        int maxRows = Math.max(askRows, bidRows);
        
        // Estrazione dei prezzi
        List<Integer> askPrices = new ArrayList<>();
        List<Integer> bidPrices = new ArrayList<>();
        
        if(askMap != null){
            for(String key : askMap.keySet()){
                askPrices.add(Integer.parseInt(key));
            }
        }
        
        if(bidMap != null){
            for(String key : bidMap.keySet()){
                bidPrices.add(Integer.parseInt(key));
            }
            Collections.sort(bidPrices, Collections.reverseOrder());
        }
        
        // Stampa delle righe della tabella
        for(int i = 0; i < maxRows; i++){
            StringBuilder row = new StringBuilder("| ");
            
            // Parte ASK (sinistra)
            if(i < askPrices.size()){
                // Estrazione del prezzo dall'array e recupero dell'oggeto JSON nella askMap
                int price = askPrices.get(i);
                JsonObject orders = askMap.getAsJsonObject(String.valueOf(price));
                
                int size = orders.get("size").getAsInt();
                
                row.append(String.format(" %-8d | %-10d ", price, size));
            } else {
                row.append("          |            ");
            }
            
            // Parte BID (destra)
            if(i < bidPrices.size()){
                // Estrazione del prezzo dall'array e recupero dell'oggeto JSON nella bidMap
                int price = bidPrices.get(i);
                JsonObject orders = bidMap.getAsJsonObject(String.valueOf(price));
                
                int size = orders.get("size").getAsInt();
                
                row.append(String.format("| %-8d | %-11d |", price, size));
            } else {
                row.append("|          |             |"); 
            }
            
            output.append(row).append("\n"); //Aggiunge riga alla tabella
        }
        
        output.append("+----------+-------------+----------+-------------+\n");
        output.append("                    Spread: ").append(spread).append("\n");
        
        printer.print(output.toString());  //Stampa la tabella
    }

    //Metodo per mostrare gli stop orders al client in modo formattato
    public void showStopOrders(JsonObject obj) {
        try{
            // Verifica della presenza dell'array stopOrders
            if(!obj.has("stopOrders") || !obj.get("stopOrders").isJsonArray()){
                printer.print("Errore: stopOrders array non presente o non valido");
                return;
            }

            JsonArray stopOrdersArray = obj.getAsJsonArray("stopOrders");
            StringBuilder output = new StringBuilder();
            output.append("\n====================== STOP ORDERS ======================\n");
            output.append("+--------+----------+------------+--------+-------------+\n");
            output.append("|   ID   |   Type   | Stop Price |   Qty  |    User     |\n");
            output.append("+--------+----------+------------+--------+-------------+\n");
            

            if(stopOrdersArray != null && !stopOrdersArray.isEmpty()){
                for(JsonElement element : stopOrdersArray){
                    if (!element.isJsonObject()) continue;
                    
                    JsonObject order = element.getAsJsonObject();
                    
                    // Estrazione dei valori con valori di default
                    int id = order.has("orderID") ? order.get("orderID").getAsInt() : -1;
                    String type = order.has("type") ? order.get("type").getAsString() : "N/A";
                    int price = order.has("stopPrice") ? order.get("stopPrice").getAsInt() : -1;
                    int qty = order.has("size") ? order.get("size").getAsInt() : -1;
                    String user = order.has("username") ? order.get("username").getAsString() : "N/A";
                        
                    // Formattazione della riga
                    output.append(String.format("| %-6d | %-8s | %-10d | %-6d | %-11s |\n", id, type, price, qty, user));
                }
            } else {
                output.append("|               No Stop Orders presenti                 |\n");
            }

            output.append("+--------+----------+------------+--------+-------------+\n");
            printer.print(output.toString());
        } catch (Exception e) {
            printer.print("Errore in showStopOrders: " + e.getMessage());
        }
    }
}