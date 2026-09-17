package ood.parkingLot;

import java.time.Duration;
import java.time.LocalDateTime;

public class Fee {
//fee(money,leave time， receipt)                                           //fee calculate base on the housr and size of the car and pay
    private final Receipt receipt;

    private final LocalDateTime endTime;

    private final double fee;

    private double calculateFee() {
        Size carSize = receipt.getCar().getCarSize();
        LocalDateTime starTime = receipt.getStarTime();
        long hours = Duration.between(starTime, endTime).toHours();

        double ratePerHours = 0;

        switch (carSize) {
            case SMALL:
                ratePerHours = 0.25;
                break;
            case MEDIUM:
                ratePerHours = 0.5;
                break;
            case LARGE:
                ratePerHours = 0.75;
                break;
            default:
                throw new IllegalArgumentException("Invalid size");

        }

        return  ratePerHours * hours;
    }


    public Fee(Receipt receipt, LocalDateTime endTime) {
        this.receipt = receipt;
        this.endTime = endTime;
        this.fee = calculateFee();
    }

    public double getFee() {
        return fee;
    }

    public Receipt getReceipt() {
        return receipt;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }


}
