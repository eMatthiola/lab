package ood.parkingLot;

public class ParkingSpot {

    //parking spot（size(small, middle, big), avaliable or not, spot number）    //find avalibale parking spot base on the car size
    private boolean available;

    private final Integer spotNumber;

    private final  Size parkingSpotSize;



    public boolean canFit(Car car) {

        return available && parkingSpotSize.ordinal()>= car.getCarSize().ordinal();

    }


    public ParkingSpot(Integer spotNumber, Size parkingSpotSize, boolean available) {
        this.spotNumber = spotNumber;
        this.parkingSpotSize = parkingSpotSize;
        this.available = available;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public Integer getSpotNumber() {
        return spotNumber;
    }

    public Size getParkingSpotSize() {
        return parkingSpotSize;
    }
}
