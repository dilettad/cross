package Eseguibili.Server;

import java.net.InetAddress;

//Classe utilizzata per mantenere la porta e l'indirizzo del cliente caricati nella mappa che tiene traccia di tutti gli utenti attivi

public class SockMapValue {
    public int port;
    public InetAddress address;

    //Costruttore: inizializza assegnando i valori passati come argomenti ai campi della classe
    public SockMapValue(int port, InetAddress address) {
        this.port = port;
        this.address = address;
    }
    
    //Metodo che restituisce la rappresentazione testuale dell'oggetto 
    public String toString(){
        return "SockMapValue{" +
                "port=" + port +
                ", address=" + address +
                '}';
    }
}
