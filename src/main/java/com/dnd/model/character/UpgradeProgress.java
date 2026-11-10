package com.dnd.model.character;

import java.util.ArrayList;
import java.util.List;

/** Saved, immutable-in-use reward snapshots survive changes to a GM's tree. */
public class UpgradeProgress {
    private int earned;
    private List<Unlock> unlocks = new ArrayList<>();
    public int getEarned() { return earned; }
    public void setEarned(int value) { earned = value; }
    public List<Unlock> getUnlocks() { return unlocks; }
    public void setUnlocks(List<Unlock> value) { unlocks = value == null ? new ArrayList<>() : value; }

    public static class Unlock {
        private String classId;
        private String nodeId;
        private String branchName;
        private String name;
        private String description;
        private String imagePath;
        private int level;
        private boolean historical;
        private List<String> exclusiveWith = new ArrayList<>();
        private UpgradeTree.Reward reward = new UpgradeTree.Reward();
        public String getClassId() { return classId; }
        public void setClassId(String value) { classId = value; }
        public String getNodeId() { return nodeId; }
        public void setNodeId(String value) { nodeId = value; }
        public String getBranchName() { return branchName; }
        public void setBranchName(String value) { branchName = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public String getDescription() { return description; }
        public void setDescription(String value) { description = value; }
        public String getImagePath() { return imagePath; }
        public void setImagePath(String value) { imagePath = value; }
        public int getLevel() { return level; }
        public void setLevel(int value) { level = value; }
        public boolean isHistorical() { return historical; }
        public void setHistorical(boolean value) { historical = value; }
        public List<String> getExclusiveWith() { return exclusiveWith; }
        public void setExclusiveWith(List<String> value) { exclusiveWith = value == null ? new ArrayList<>() : value; }
        public UpgradeTree.Reward getReward() { return reward; }
        public void setReward(UpgradeTree.Reward value) { reward = value == null ? new UpgradeTree.Reward() : value; }
    }
}
