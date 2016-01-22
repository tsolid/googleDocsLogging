/**
 * SmartThings example Code for Google sheets logging
 *
 * Copyright 2016 Charles Schwer
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 *
 */

definition(
		name: "Google Sheets Logging",
		namespace: "cschwer",
		author: "Charles Schwer",
		description: "Log to Google Sheets",
		category: "My Apps",
		iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
		iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
		iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png")

preferences {
	section("Log devices...") {
		input "contacts", "capability.contactSensor", title: "Doors open/close", required: false, multiple: true
		input "temperatures", "capability.temperatureMeasurement", title: "Temperatures", required:false, multiple: true
        input "thermostatHeatSetPoint", "capability.thermostat", title: "Thermostat Heat Setpoints", required: false, multiple: true
        input "energyMeters", "capability.energyMeter", title: "Energy Meters", required: false, multiple: true
        input "powerMeters", "capability.powerMeter", title: "Power Meters", required: false, multiple: true
	}

	section ("Google Sheets script url key...") {
		input "urlKey", "text", title: "URL key"
	}
    
    section ("Technical settings") {
        input "queueTime", "enum", title:"Time to queue events before pushing to Google (in minutes)", options: ["0", "5", "10", "15"], defaultValue:"5"
        input "resetVals", "enum", title:"Reset the state values (queue, schedule, etc)", options: ["yes", "no"], defaultValue: "no"
    }
}

def installed() {
	setOriginalState()
	initialize()
}

def updated() {
	log.debug "Updated"
	unsubscribe()
	initialize()
    if(settings.resetVals == "yes") {
    	setOriginalState()
        settings.resetVals = "no"
    }
}

def initialize() {
	log.debug "Initialized"
	subscribe(temperatures, "temperature", handleTemperatureEvent)
	subscribe(contacts, "contact", handleContactEvent)
    subscribe(thermostatHeatSetPoint, "heatingSetpoint", handleTemperatureEvent)
    subscribe(energyMeters, "energy", handleEnergyEvent)
    subscribe(powerMeters, "power", handleEnergyEvent)
}

def setOriginalState() {
	log.debug "Set original state"
	unschedule()
	state.queue = [:]
    state.failureCount=0
    state.scheduled=false
    state.lastSchedule=0
}

def handleEnergyEvent(evt) {
	if(settings.queueTime > 0) {
    	queueValue(evt) { it.toString() }
    } else {
    	sendValue(evt) { it.toString() }
    }
}

def handleTemperatureEvent(evt) {
	if(settings.queueTime > 0) {
    	queueValue(evt) { it.toString() }
    } else {
    	sendValue(evt) { it.toString() }
    }
}

def handleContactEvent(evt) {
	sendValue(evt) { it == "open" ? "true" : "false" }
}

private sendValue(evt, Closure convert) {
	def keyId = URLEncoder.encode(evt.displayName.trim()+ " " +evt.name)
	def value = convert(evt.value)
    
	log.debug "Logging to GoogleSheets ${keyId} = ${value}"
    
	def url = "https://script.google.com/macros/s/${urlKey}/exec?${keyId}=${value}"
    log.debug "${url}"
    
	def putParams = [
		uri: url]

	httpGet(putParams) { response ->
    	log.debug(response.status)
		if (response.status != 200 ) {
			log.debug "Google logging failed, status = ${response.status}"
		}
	}
}

private queueValue(evt, Closure convert) {
	checkAndProcessQueue()
	if( evt?.value ) {
    	
    	def keyId = URLEncoder.encode(evt.displayName.trim()+ " " +evt.name)
		def value = convert(evt.value)
    
    	log.debug "Logging to queue ${keyId} = ${value}"
    
		if( state.queue == [:] ) {
      		def eventTime = URLEncoder.encode(evt.date.format( 'M-d-yyyy HH:mm:ss', location.timeZone ))
      		state.queue.put("Time", eventTime)
    	}
    
    	state.queue.put(keyId, value)
        log.debug(state.queue)

    	scheduleQueue()
	}
}

private checkAndProcessQueue() {
    if (state.scheduled && ((now() - state.lastSchedule) > (settings.queueTime.toInteger()*120000))) {
		// if event has been queued for twice the amount of time it should be, then we are probably stuck
        sendEvent(name: "scheduleFailure", value: now())
        unschedule()
    	processQueue()
    }
}

def scheduleQueue() {
	if(state.failureCount >= 3) {
	    log.debug "Too many failures, clearing queue"
        sendEvent(name: "queueFailure", value: now())
        resetState()
    }
	
    if(!state.scheduled) {
    	runIn(settings.queueTime.toInteger() * 60, processQueue)
        state.scheduled=true
        state.lastSchedule=now()
    } 
}


private resetState() {
	state.queue = [:]
    state.failureCount=0
    state.scheduled=false
}

def processQueue() {
	state.scheduled=false
    log.debug "Processing Queue"
    if (state.queue != [:]) {
        def url = "https://script.google.com/macros/s/${urlKey}/exec?"
        for ( e in state.queue ) { url+="${e.key}=${e.value}&" }
        url = url[0..-2]
        log.debug(url)
        try {
			def putParams = [
				uri: url]

			httpGet(putParams) { response ->
    			log.debug(response.status)
				if (response.status != 200 ) {
					log.debug "Google logging failed, status = ${response.status}"
                    state.failureCount++
                    scheduleQueue()
				} else {
        			log.debug "Google accepted event(s)"
                    resetState()
        		}
			}
        } catch(e) {
            def errorInfo = "Error sending value: ${e}"
            log.error errorInfo
        }
	}
}
