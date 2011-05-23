Garden Monitor
==============
An Android app that communicates with an Arduino using the Open Accessory API to monitor a garden.

A video of it in action is available on [Youtube](http://www.youtube.com/watch?v=3GPnpmZnUjE).

Arduino
-------
The included Arduino firmware reads data from an LM34DZ temperature sensor, HS1101 humidity sensor, and a force-sensing resistor and can control 1 servo for opening / closing the water tank door.

Android
-------
The Android app receives the telemetry from the Arduino sensors and broadcasts them over the network using the XPL protocol for interfacing with home automation software.  The app monitors the water level, and when it falls below 15% it will open the water tank door for refilling.  Once the tank has been refilled, the app will close the door again.  The Android app also snaps a photo every hour and uploads it via HTTP POST to a URL.

MisterHouse
-----------
A sample MisterHouse script that monitors the XPL broadcasts from the Android app is included in the "misterhouse" folder.

License
-------
This source code is released under the terms of the [GNU General Public License](http://www.gnu.org/licenses/gpl.html) version 3 or later.