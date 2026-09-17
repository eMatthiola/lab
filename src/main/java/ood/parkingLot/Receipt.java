package ood.parkingLot;

import java.time.LocalDateTime;

public class Receipt {
    //receipt（lisense number，spot number, start time， receipt number）          //  get a receipt when coming
    //not only lisense number but also the size of the car to calculate the fee
    private final Car car;

    private final ParkingSpot parkingSpot;

    private final LocalDateTime starTime;

    private final String receiptNumber;

    public Receipt(Car car, ParkingSpot parkingSpot, LocalDateTime starTime, String receiptNumber) {
        this.car = car;
        this.parkingSpot = parkingSpot;
        this.starTime = starTime;
        this.receiptNumber = receiptNumber;
    }

    public Car getCar() {
        return car;
    }

    public ParkingSpot getParkingSpot() {
        return parkingSpot;
    }

    public LocalDateTime getStarTime() {
        return starTime;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }
}
