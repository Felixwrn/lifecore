package de.felix.lifeplugin.challenge;

public class Challenge {

    private final String id;
    private final String name;
    private final String type;
    private final int goal;
    private final int reward;

    public Challenge(
            String id,
            String name,
            String type,
            int goal,
            int reward
    ) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.goal = goal;
        this.reward = reward;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public int getGoal() {
        return goal;
    }

    public int getReward() {
        return reward;
    }
}
