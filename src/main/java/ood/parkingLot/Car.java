package ood.parkingLot;

public class Car {
    //car(lisense number, size)                                                 //requestParking

    private final String lisenseNumber;

    private final Size carSize;

    public Car(String lisenseNumber, Size carSize) {
        this.lisenseNumber = lisenseNumber;
        this.carSize = carSize;
    }

    public Size getCarSize() {
        return carSize;
    }


    public String getLisenseNumber() {
        return lisenseNumber;
    }


}
