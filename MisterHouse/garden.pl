# Define the sensor objects in your items.mht as follows:
#
# X10A, A1, garden
# XPL_SENSOR, c99org-garden.0001, garden_temperature, , temp
# XPL_SENSOR, c99org-garden.0001, garden_humidity, , humidity
# XPL_SENSOR, c99org-garden.0001, garden_waterlevel, , waterlevel
#

use Weather_Common;

$garden_temperature->manage_heartbeat_timeout(360, "speak 'Garden controller is offline'", 1); # noloop
$garden_temperature->tie_event('garden_temp_change_hook()'); # noloop
$garden_humidity->tie_event('garden_humidity_change_hook()'); # noloop
$garden_waterlevel->tie_event('garden_waterlevel_change_hook()'); # noloop

sub garden_temp_change_hook {
        $Weather{TempIndoor} = state $garden_temperature;
        &Weather_Common::weather_updated;
}

sub garden_humidity_change_hook {
        $Weather{HumidIndoor} = state $garden_humidity;
        &Weather_Common::weather_updated;
}

sub garden_waterlevel_change_hook {
# todo: email an alert when the water level is low
}

if(time_now "6:00 am") {
        set $garden 'on';
}

if(time_now "10:00 pm") {
        set $garden 'off';
}

