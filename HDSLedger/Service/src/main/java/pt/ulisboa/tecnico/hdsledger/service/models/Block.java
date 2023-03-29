package pt.ulisboa.tecnico.hdsledger.service.models;

public class Block {

    // Consensus instance 
    private int consensusInstance;
    // Hash of the previous block
    private String previousHash;
    // List of (ordered) transactions
    private List<Transaction> transactions;
    
}