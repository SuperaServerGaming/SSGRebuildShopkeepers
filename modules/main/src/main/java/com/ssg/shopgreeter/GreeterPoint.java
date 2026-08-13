package com.ssg.shopgreeter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class GreeterPoint {
    private final UUID shopkeeperId;
    private final List<String> messages = new ArrayList<>();
    private double radius;

    public GreeterPoint(UUID shopkeeperId, double radius) {
        this.shopkeeperId = shopkeeperId;
        this.radius = radius;
    }

    public UUID getShopkeeperId() {
        return shopkeeperId;
    }

    public List<String> getMessages() {
        return messages;
    }

    public double getRadius() {
        return radius;
    }

    public void setRadius(double radius) {
        this.radius = radius;
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }
}
