/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.mdm.mobileservices.windows.services.syncml.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.wso2.carbon.device.mgt.common.*;
import org.wso2.carbon.device.mgt.common.operation.mgt.Operation;
import org.wso2.carbon.device.mgt.common.operation.mgt.OperationManagementException;
import org.wso2.carbon.mdm.mobileservices.windows.common.beans.CacheEntry;
import org.wso2.carbon.mdm.mobileservices.windows.common.exceptions.WindowsDeviceEnrolmentException;
import org.wso2.carbon.mdm.mobileservices.windows.common.util.DeviceUtil;
import org.wso2.carbon.mdm.mobileservices.windows.common.util.WindowsAPIUtils;
import org.wso2.carbon.mdm.mobileservices.windows.operations.*;
import org.wso2.carbon.mdm.mobileservices.windows.operations.util.OperationReply;
import org.wso2.carbon.mdm.mobileservices.windows.operations.util.SyncmlGenerator;
import org.wso2.carbon.mdm.mobileservices.windows.operations.util.SyncmlParser;
import org.wso2.carbon.mdm.mobileservices.windows.services.syncml.SyncmlService;
import org.wso2.carbon.mdm.mobileservices.windows.services.syncml.util.SyncmlUtils;

import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementing class of SyncmlImpl interface.
 */
public class SyncmlServiceImpl implements SyncmlService {

	private static final int SYNCML_FIRST_MESSAGE = 1;
	private static final int SYNCML_SECOND_MESSAGE = 2;
	private static final int SESSIONID_FIRST = 1;
	private static final int SESSIONID_SECOND = 2;
	private static final int OSVERSION_POSITION = 0;
	private static final int DEVICE_ID_POSITION = 0;
	private static final int DEVICE_MODE_POSITION = 2;
	private static final int DEVICE_MAN_POSITION = 1;
	private static final int DEVICE_MOD_VER_POSITION = 3;
	private static final int DEVICE_LANG_POSITION = 4;
	private static final int IMSI_POSITION = 1;
	private static final int IMEI_POSITION = 2;
	private static final int VENDER_POSITION =  7;
	private static final int MACADDRESS_POSITION = 8;
	private static final int RESOLUTION_POSITION = 9;
	private static final String OS_VERSION = "OS_VERSION";
	private static final String IMSI = "IMSI";
	private static final String IMEI = "IMEI";
	private static final String VENDOR = "VENDER";
	private static final String MODEL = "DEVICE_MODEL";

	private static Log log = LogFactory.getLog(SyncmlServiceImpl.class);

	/**
	 * This method is used to generate and return Device object from the received information at
	 * the Syncml step.
	 *
	 * @param deviceID     - Unique device ID received from the Device
	 * @param osVersion    - Device OS version
	 * @param imsi         - Device IMSI
	 * @param imei         - Device IMEI
	 * @param manufacturer - Device Manufacturer name
	 * @param model        - Device Model
	 * @return - Generated device object
	 */
	private Device generateDevice(String type, String deviceID, String osVersion, String imsi,
								  String imei, String manufacturer, String model, String user) {

		Device generatedDevice = new Device();

		Device.Property OSVersionProperty = new Device.Property();
		OSVersionProperty.setName(OS_VERSION);
		OSVersionProperty.setValue(osVersion);

		Device.Property IMSEIProperty = new Device.Property();
		IMSEIProperty.setName(SyncmlServiceImpl.IMSI);
		IMSEIProperty.setValue(imsi);

		Device.Property IMEIProperty = new Device.Property();
		IMEIProperty.setName(SyncmlServiceImpl.IMEI);
		IMEIProperty.setValue(imei);

		Device.Property DevManProperty = new Device.Property();
		DevManProperty.setName(VENDOR);
		DevManProperty.setValue(manufacturer);

		Device.Property DevModProperty = new Device.Property();
		DevModProperty.setName(MODEL);
		DevModProperty.setValue(model);

		List<Device.Property> propertyList = new ArrayList<Device.Property>();
		propertyList.add(OSVersionProperty);
		propertyList.add(IMSEIProperty);
		propertyList.add(IMEIProperty);
		propertyList.add(DevManProperty);
		propertyList.add(DevModProperty);

		EnrolmentInfo enrolmentInfo = new EnrolmentInfo();
		enrolmentInfo.setOwner(user);
		enrolmentInfo.setOwnership(EnrolmentInfo.OwnerShip.BYOD);
		enrolmentInfo.setStatus(EnrolmentInfo.Status.ACTIVE);

		generatedDevice.setEnrolmentInfo(enrolmentInfo);
		generatedDevice.setDeviceIdentifier(deviceID);
		generatedDevice.setProperties(propertyList);
		generatedDevice.setType(type);

		return generatedDevice;
	}

	/**
	 * Method for calling SyncML engine for producing the Syncml response. For the first SyncML message comes from
	 * the device, this method produces a response to retrieve device information for enrolling the device.
	 *
	 * @param request - SyncML request
	 * @return - SyncML response
	 * @throws WindowsOperationException
	 * @throws WindowsDeviceEnrolmentException
	 */
	@Override
	public Response getResponse(Document request) throws WindowsDeviceEnrolmentException, WindowsOperationException {

		SyncmlDocument syncmlDocument = SyncmlParser.parseSyncmlPayload(request);
		int sessionId = syncmlDocument.getHeader().getSessionId();
		String user = syncmlDocument.getHeader().getSource().getLocName();
		int msgID = syncmlDocument.getHeader().getMsgID();

		DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
		deviceIdentifier.setId(syncmlDocument.getHeader().getSource().getLocURI());
		deviceIdentifier.setType(DeviceManagementConstants.MobileDeviceTypes.MOBILE_DEVICE_TYPE_WINDOWS);
		List<Operation> deviceInfoList;
		List<? extends Operation> pendingOperations;
		String response;

		if (SYNCML_FIRST_MESSAGE == msgID && SESSIONID_FIRST == sessionId) {

			String token = syncmlDocument.getHeader().getCredential().getData();
			CacheEntry ob = (CacheEntry) DeviceUtil.getCacheEntry(token);
			if (ob.getUsername().equals(user)) {

				enrolDevice(request);
				deviceInfoList = new ArrayList<>();
				Operation osVersion = new Operation();
				osVersion.setCode("SOFTWARE_VERSION");
				osVersion.setType(Operation.Type.INFO);
				deviceInfoList.add(osVersion);

				Operation imsi = new Operation();
				imsi.setCode("IMSI");
				imsi.setType(Operation.Type.INFO);
				deviceInfoList.add(imsi);

				Operation imei = new Operation();
				imei.setCode("IMEI");
				imei.setType(Operation.Type.INFO);
				deviceInfoList.add(imei);

				Operation deviceID = new Operation();
				deviceID.setCode("DEV_ID");
				deviceID.setType(Operation.Type.INFO);
				deviceInfoList.add(deviceID);

				Operation manufacturer = new Operation();
				manufacturer.setCode("MANUFACTURER");
				manufacturer.setType(Operation.Type.INFO);
				deviceInfoList.add(manufacturer);

				Operation model = new Operation();
				model.setCode("MODEL");
				model.setType(Operation.Type.INFO);
				deviceInfoList.add(model);

				Operation language = new Operation();
				language.setCode("LANGUAGE");
				language.setType(Operation.Type.INFO);
				deviceInfoList.add(language);

				Operation vender = new Operation();
				vender.setCode("VENDER");
				vender.setType(Operation.Type.INFO);
				deviceInfoList.add(vender);

				Operation macaddress = new Operation();
				macaddress.setCode("MAC_ADDRESS");
				macaddress.setType(Operation.Type.INFO);
				deviceInfoList.add(macaddress);

				Operation resolution = new Operation();
				resolution.setCode("RESOLUTION");
				resolution.setType(Operation.Type.INFO);
				deviceInfoList.add(resolution);

				OperationReply operationReply = new OperationReply(syncmlDocument, deviceInfoList);
				SyncmlDocument syncmlResponse = operationReply.generateReply();
				SyncmlGenerator generator = new SyncmlGenerator();
				response = generator.generatePayload(syncmlResponse);
				return Response.ok().entity(response).build();

			} else {
				String msg = "Authentication failure due to incorrect credentials.";
				log.error(msg);
				return Response.status(401).entity("Authentication failure").build();
			}
		} else if (SYNCML_SECOND_MESSAGE == msgID && SESSIONID_FIRST == sessionId) {
            enrolDevice(request);
			OperationReply operationReply = new OperationReply(syncmlDocument);
			SyncmlDocument syncmlResponse = operationReply.generateReply();
			SyncmlGenerator generator = new SyncmlGenerator();
			response = generator.generatePayload(syncmlResponse);
			return Response.ok().entity(response).build();

		} else if (sessionId >= SESSIONID_SECOND) {

			try {
				    pendingOperations = SyncmlUtils.getDeviceManagementService().getPendingOperations(deviceIdentifier);
					OperationReply operationReply = new OperationReply(syncmlDocument, (List<Operation>) pendingOperations);
					SyncmlDocument syncmlResponse = operationReply.generateReply();
					SyncmlGenerator generator = new SyncmlGenerator();
					response = generator.generatePayload(syncmlResponse);
				    return Response.ok().entity(response).build();

				} catch (OperationManagementException e) {
					String msg = "Cannot access operation management service.";
					log.error(msg);
					throw new WindowsOperationException(msg, e);
			    }
			}

		return Response.status(400).entity("Authentication failure").build();
	}
	private boolean enrolDevice(Document request) throws WindowsOperationException, WindowsDeviceEnrolmentException {

		SyncmlDocument syncmlDocument = SyncmlParser.parseSyncmlPayload(request);
		int msgID;
		String osVersion;
		String imsi = null;
		String imei = null;
		String devID;
		String devMan;
		String devMod;
		String devLang;
		String vender;
		String macAddress;
		String resolution;
		String modVersion;
		boolean status;
		String user;

		msgID = syncmlDocument.getHeader().getMsgID();
		if (msgID == SYNCML_FIRST_MESSAGE) {
			Replace replace = syncmlDocument.getBody().getReplace();
			List<Item> itemList = replace.getItems();
			devID = itemList.get(DEVICE_ID_POSITION).getData();
			devMan = itemList.get(DEVICE_MAN_POSITION).getData();
			devMod = itemList.get(DEVICE_MODE_POSITION).getData();
			modVersion = itemList.get(DEVICE_MOD_VER_POSITION).getData();
			devLang = itemList.get(DEVICE_LANG_POSITION).getData();
			user = syncmlDocument.getHeader().getSource().getLocName();

			if (log.isDebugEnabled()) {
				log.debug(
						"OS Version:" + modVersion + ", DevID: " + devID + ", DevMan: " + devMan +
								", DevMod: " + devMod + ", DevLang: " + devLang);
			}
			Device generateDevice = generateDevice(DeviceManagementConstants.MobileDeviceTypes.
					MOBILE_DEVICE_TYPE_WINDOWS, devID, modVersion, imsi, imei, devMan, devMod, user);
			try {
				status = WindowsAPIUtils.getDeviceManagementService().enrollDevice(generateDevice);
				return status;
			} catch (DeviceManagementException e) {
                String msg = "Failure occurred in enrolling device.";
				log.debug(msg,e);

				return false;
			}
		} else if (msgID == SYNCML_SECOND_MESSAGE) {

			Results results = syncmlDocument.getBody().getResults();
			List<Item> itemList = results.getItem();
			osVersion = itemList.get(OSVERSION_POSITION).getData();
			imsi = itemList.get(IMSI_POSITION).getData();
			imei = itemList.get(IMEI_POSITION).getData();
			vender = itemList.get(VENDER_POSITION).getData();
			macAddress = itemList.get(MACADDRESS_POSITION).getData();
			resolution = itemList.get(RESOLUTION_POSITION).getData();

			DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
			deviceIdentifier.setId(syncmlDocument.getHeader().getSource().getLocURI());
			deviceIdentifier.setType(DeviceManagementConstants.MobileDeviceTypes.MOBILE_DEVICE_TYPE_WINDOWS);
			try {
				Device existingDevice = WindowsAPIUtils.getDeviceManagementService().getDevice(deviceIdentifier);
				if (existingDevice.getProperties() == null) {
					List<Device.Property> existingProperties = new ArrayList<Device.Property>();

					Device.Property imeiProperty = new Device.Property();
					imeiProperty.setName("IMEI");
					imeiProperty.setValue(imei);
					existingProperties.add(imeiProperty);

					Device.Property osVersionProperty = new Device.Property();
					osVersionProperty.setName("OS_VERSION");
					osVersionProperty.setValue(osVersion);
					existingProperties.add(osVersionProperty);

					Device.Property imsiProperty = new Device.Property();
					imsiProperty.setName("IMSI");
					imsiProperty.setValue(imsi);
					existingProperties.add(imsiProperty);

					Device.Property venderProperty = new Device.Property();
					venderProperty.setName("VENDOR");
					venderProperty.setValue(vender);
					existingProperties.add(venderProperty);

					Device.Property macAddressProperty = new Device.Property();
					macAddressProperty.setName("MAC_ADDRESS");
					macAddressProperty.setValue(macAddress);
					existingProperties.add(macAddressProperty);

					Device.Property resolutionProperty = new Device.Property();
					resolutionProperty.setName("DEVICE_INFO");
					resolutionProperty.setValue(resolution);
					existingProperties.add(resolutionProperty);


					existingDevice.setProperties(existingProperties);
					existingDevice.setDeviceIdentifier(syncmlDocument.getHeader().getSource().getLocURI());
					existingDevice.setType(DeviceManagementConstants.MobileDeviceTypes.MOBILE_DEVICE_TYPE_WINDOWS);
					WindowsAPIUtils.getDeviceManagementService().modifyEnrollment(existingDevice);
				}

			} catch (DeviceManagementException e) {
				String msg = "Enrollment modification fail.";
				log.error(msg);
			}


		}
		return false;
	}

}
