#include <Servo.h>
#include <Usb.h>
#include <AndroidAccessory.h>

int tempPin = A12;
int doorServoPin = 10;
int humidPin = 22;
int waterLevelPin = A9;

Servo door;
boolean doorClosed;

AndroidAccessory acc("Sam Steele",
		     "GardenMonitor",
		     "GardenMonitor Arduino Board",
		     "1.0",
		     "http://www.c99.org",
		     "0001");

void setup() {                
  Serial.begin(9600);
  Serial.println("Start");
  pinMode(tempPin, INPUT);
  pinMode(waterLevelPin, INPUT);
  door.attach(doorServoPin);
  door.write(100);
  doorClosed = true;
  acc.powerOn();
}

void close_door() {
  if(!doorClosed) {
    for(int i = 0; i <= 100; i++) {
      door.write(i);
      delay(10);
    }
    doorClosed = true;
  }
}

void open_door() {
  if(doorClosed) {
    for(int i = 100; i >= 0; i--) {
      door.write(i);
      delay(10);
    }
    doorClosed = false;
  }
}

unsigned long RCtime(int sensPin){
   pinMode(sensPin, OUTPUT);       // make pin OUTPUT
   digitalWrite(sensPin, HIGH);    // make pin HIGH to discharge capacitor - study the schematic
   delay(1);                       // wait a  ms to make sure cap is discharged

   pinMode(sensPin, INPUT);        // turn pin into an input and time till pin goes low
   digitalWrite(sensPin, LOW);     // turn pullups off - or it won't work
   unsigned long startTime = micros();
   while(digitalRead(sensPin)){    // wait for pin to go low
   }

   return micros() - startTime;                   // report results   
}

int temperature() {
  unsigned long val = 0;
  //Take 100 readings and average them
  for(int i = 0; i < 100; i++) {
    val += (460.0 * analogRead(tempPin)) / 1024;
  }
  val /= 100;
  return val;
}

int humidity() {
  unsigned long val = 0;
  //Take 100 readings and average them
  for(int i = 0; i < 100; i++) {
    val += (((RCtime(humidPin) / 10) - 163) / 40.0F) * 100.0F;
  }
  val /= 100;
  return val;
}

int water_level() {
  unsigned long val = 0;
  //Take 100 readings and average them
  for(int i = 0; i < 100; i++) {
      val += analogRead(waterLevelPin);
  }
  val /= 100;
  
  if(val < 650)
    val = 650;
  if(val > 750)
    val = 750;
  return val - 650;
}

void loop() {
  byte err;
  byte idle;
  static byte count = 0;
  byte msg[3];

  /*Serial.print("Temp: ");
  Serial.println(temperature());
  Serial.print("Humid: ");
  Serial.println(humidity());
  Serial.print("Water level: ");
  Serial.println(water_level());*/

  if (acc.isConnected()) {
    int len = acc.read(msg, sizeof(msg), 1);
    int i;
    byte b;
    uint16_t val;
    int x, y;
    char c0;

    if (len > 0) {
      switch(msg[0]) {
        case 0x01:
          open_door();
          break;
        case 0x02:
          close_door();
          break;
      }
    }
    
    switch (count++ % 3) {
      case 0:
        val = temperature();
	msg[0] = 0x1;
	msg[1] = val >> 8;
	msg[2] = val & 0xff;
	acc.write(msg, 3);
        Serial.print("Sending temp: ");
        Serial.println(val);
	break;
      case 1:
        val = humidity();
	msg[0] = 0x2;
	msg[1] = val >> 8;
	msg[2] = val & 0xff;
	acc.write(msg, 3);
        Serial.print("Sending humidity: ");
        Serial.println(val);
	break;
      case 2:
        val = water_level();
	msg[0] = 0x3;
	msg[1] = val >> 8;
	msg[2] = val & 0xff;
	acc.write(msg, 3);
        Serial.print("Sending water level: ");
        Serial.println(val);
	break;
    }
  }
  delay(100);
}
