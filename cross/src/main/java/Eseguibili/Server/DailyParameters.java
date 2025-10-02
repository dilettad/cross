package Eseguibili.Server;

//Classe che rappresenta le statistiche giornaliere inviate al client che ha richiesto lo storico dei prezzi.
//Contiene i prezzi di apertura, chiusura, massimo e minimo, timestamp della prima e dell'ultima transazione.

public class DailyParameters {
    public final String date;               
    public int openPrice;                   
    public int closePrice;          
    public int highPrice;
    public int lowPrice;
    public long firstTimestamp;
    public long lastTimestamp;

//Costruttore: inizializza istanza a partire dalla prima transazione del giorno
    public DailyParameters(String date, int price, long timestamp) {
        this.date = date;
        this.openPrice = price;
        this.closePrice = price;
        this.highPrice = price;
        this.lowPrice = price;
        this.firstTimestamp = timestamp;
        this.lastTimestamp = timestamp;
    }
    
    //Metodo per aggiornare i parametri giornalieri con una nuova transazione
    public void updatePrices (int price, long timestamp){
        //Aggiora high e low
        if (price > highPrice) {
            highPrice = price;
        }
        if (price < lowPrice) {
            lowPrice = price;
        }
        //Aggiorna open se trade è più vecchio del primo conosciuto
        if (timestamp < firstTimestamp) {
            firstTimestamp = timestamp;
            openPrice = price;
        }
        //Aggiorna close se trade è più recente dell'ultimo conosciuto
        if (timestamp > lastTimestamp) {
            lastTimestamp = timestamp;
            closePrice = price;
        }
    }
}