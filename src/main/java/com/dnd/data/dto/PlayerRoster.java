package com.dnd.data.dto;

import com.dnd.model.character.PlayerCharacter;

import java.util.List;

public class PlayerRoster {
    private List<PlayerCharacter> players;
    private java.util.Set<String> battleXpReceipts = new java.util.LinkedHashSet<>();

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public java.util.Set<String> getBattleXpReceipts() { return battleXpReceipts; }

    public void setBattleXpReceipts(java.util.Set<String> receipts) {
        battleXpReceipts = receipts == null ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(receipts);
    }

    public PlayerRoster() {
    }

    public List<PlayerCharacter> getPlayers() {
        return players;
    }

    public void setPlayers(List<PlayerCharacter> players) {
        this.players = players;
    }
}

