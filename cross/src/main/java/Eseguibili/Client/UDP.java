package Eseguibili.Client;

import java.io.BufferedReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;

import OrderBook.Trade;

//Classe per la ricezione UDP
public class UDP implements Runnable {
    public DatagramSocket socket;
    public BufferedReader in;
    public Printer printer;

    //Costruttore
    public UDP(DatagramSocket socket, BufferedReader in, Printer printer) {
        this.socket = socket;
        this.in = in;
        this.printer = printer;
    }

    public void run(){
        // Ciclo infinito per la ricezione dei messaggi UDP
        while (true) { 
            try { 
                // Creo il buffer per contenere i messaggi ricevuti
                byte[] receiveData = new byte[1024];
                // Creo un DatagramPacket che riceverà i dati dal socket
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                socket.receive(receivePacket); //Ricevo il pacchetto
                //Conversione dei dati in JSON
                String jsonString = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8); 
                Gson gson = new Gson();
                //Conversione del JSON in oggetto Trade
                Trade receivedTrade = gson.fromJson(jsonString, Trade.class);

                // Estrazione dei valori
                int orderID = receivedTrade.getOrderID();
                String type = receivedTrade.getType();
                String orderType = receivedTrade.getOrderType();
                int size = receivedTrade.getSize();
                int price = receivedTrade.getPrice();
                
                // Stampa della stringa di notifica
                if(size == 0 && price == 0){
                    //Caso di fallimento 
                    printer.print("\nIl tuo " + orderType + " ordine con ID " + orderID + " di tipo " + type + " è stato elaborato ma non è andato a buon fine.");
                } else{
                    //Caso di successo
                    printer.print("\nIl tuo " + orderType + " ordine con ID " + orderID + " di tipo " + type + " è stato elaborato con dimensione " + size + " e prezzo " + price + ".");
                }

            } catch(Exception e){
                //Eccezione: stampa errore 
                printer.print("[UDP] Errore: " + e.getMessage() + " - Causa: " + e.getCause());
                break;
            }
        }
    }
}