/*******************************************************************************
 * Copyright (c) 2014 Weasis Team.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 *******************************************************************************/
package org.weasis.dicom.op;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.ElementDictionary;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.QueryRetrieveLevel;
import org.dcm4che3.tool.findscu.FindSCU;
import org.dcm4che3.tool.findscu.FindSCU.InformationModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.param.AdvancedParams;
import org.weasis.dicom.param.DicomNode;
import org.weasis.dicom.param.DicomParam;
import org.weasis.dicom.param.DicomProgress;
import org.weasis.dicom.param.DicomState;
import org.weasis.dicom.util.StringUtil;

public class CFind {

    private static final Logger LOGGER = LoggerFactory.getLogger(CFind.class);

    public static final DicomParam PatientID = new DicomParam(Tag.PatientID);
    public static final DicomParam IssuerOfPatientID = new DicomParam(Tag.IssuerOfPatientID);
    public static final DicomParam PatientName = new DicomParam(Tag.PatientName);
    public static final DicomParam PatientBirthDate = new DicomParam(Tag.PatientBirthDate);
    public static final DicomParam PatientSex = new DicomParam(Tag.PatientSex);

    public static final DicomParam StudyInstanceUID = new DicomParam(Tag.StudyInstanceUID);
    public static final DicomParam AccessionNumber = new DicomParam(Tag.AccessionNumber);
    public static final DicomParam StudyID = new DicomParam(Tag.StudyID);
    public static final DicomParam ReferringPhysicianName = new DicomParam(Tag.ReferringPhysicianName);
    public static final DicomParam StudyDescription = new DicomParam(Tag.StudyDescription);
    public static final DicomParam StudyDate = new DicomParam(Tag.StudyDate);
    public static final DicomParam StudyTime = new DicomParam(Tag.StudyTime);

    public static final DicomParam SeriesInstanceUID = new DicomParam(Tag.SeriesInstanceUID);
    public static final DicomParam Modality = new DicomParam(Tag.Modality);
    public static final DicomParam SeriesNumber = new DicomParam(Tag.SeriesNumber);
    public static final DicomParam SeriesDescription = new DicomParam(Tag.SeriesDescription);

    public static final DicomParam SOPInstanceUID = new DicomParam(Tag.SOPInstanceUID);
    public static final DicomParam InstanceNumber = new DicomParam(Tag.InstanceNumber);

    private CFind() {
    }

    /**
     * @param callingNode
     *            the calling DICOM node configuration
     * @param calledNode
     *            the called DICOM node configuration
     * @param keys
     *            the matching and returning keys. DicomParam with no value is a returning key.
     * @return The DicomSate instance which contains the DICOM response, the DICOM status, the error message and the
     *         progression.
     */
    public static DicomState process(DicomNode callingNode, DicomNode calledNode, DicomParam... keys) {
        return process(null, callingNode, calledNode, 0, QueryRetrieveLevel.STUDY, keys);
    }

    /**
     * @param params
     *            optional advanced parameters (proxy, authentication, connection and TLS)
     * @param callingNode
     *            the calling DICOM node configuration
     * @param calledNode
     *            the called DICOM node configuration
     * @param keys
     *            the matching and returning keys. DicomParam with no value is a returning key.
     * @return The DicomSate instance which contains the DICOM response, the DICOM status, the error message and the
     *         progression.
     */
    public static DicomState process(AdvancedParams params, DicomNode callingNode, DicomNode calledNode,
        DicomParam... keys) {
        return process(params, callingNode, calledNode, 0, QueryRetrieveLevel.STUDY, keys);
    }

    /**
     * @param params
     *            optional advanced parameters (proxy, authentication, connection and TLS)
     * @param callingNode
     *            the calling DICOM node configuration
     * @param calledNode
     *            the called DICOM node configuration
     * @param cancelAfter
     *            cancel the query request after the receive of the specified number of matches.
     * @param level
     *            specifies retrieve level. Use by default STUDY for PatientRoot, StudyRoot, PatientStudyOnly model.
     * @param keys
     *            the matching and returning keys. DicomParam with no value is a returning key.
     * @return The DicomSate instance which contains the DICOM response, the DICOM status, the error message and the
     *         progression.
     */
    public static DicomState process(AdvancedParams params, DicomNode callingNode, DicomNode calledNode,
        int cancelAfter, QueryRetrieveLevel level, DicomParam... keys) {
        if (callingNode == null || calledNode == null) {
            throw new IllegalArgumentException("callingNode or calledNode cannot be null!");
        }

        FindSCU findSCU = null;
        AdvancedParams options = params == null ? new AdvancedParams() : params;

        try {
            findSCU = new FindSCU();
            Connection remote = findSCU.getRemoteConnection();
            Connection conn = findSCU.getConnection();
            options.configureConnect(findSCU.getAAssociateRQ(), remote, calledNode);
            options.configureBind(findSCU.getApplicationEntity(), conn, callingNode);

            // configure
            options.configure(conn);
            options.configureTLS(conn, remote);

            findSCU.setInformationModel(getInformationModel(options), options.getTsuidOrder(),
                options.getQueryOptions());
            if (level != null) {
                findSCU.addLevel(level.name());
            }

            for (DicomParam p : keys) {
                addAttributes(findSCU.getKeys(), p);
            }
            findSCU.setCancelAfter(cancelAfter);
            findSCU.setPriority(options.getPriority());

            ExecutorService executorService = Executors.newSingleThreadExecutor();
            ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
            findSCU.getDevice().setExecutor(executorService);
            findSCU.getDevice().setScheduledExecutor(scheduledExecutorService);
            try {
                DicomState dcmState = findSCU.getState();
                long t1 = System.currentTimeMillis();
                findSCU.open();
                findSCU.query();
                long t2 = System.currentTimeMillis();
                String timeMsg = MessageFormat.format("C-Find from {0} to {1} in {2}ms",
                    findSCU.getAAssociateRQ().getCallingAET(), findSCU.getAAssociateRQ().getCalledAET(), t2 - t1);
                forceGettingAttributes(findSCU);
                return DicomState.buildMessage(dcmState, timeMsg, null);
            } catch (Exception e) {
                LOGGER.error("findscu", e);
                forceGettingAttributes(findSCU);
                return DicomState.buildMessage(findSCU.getState(), null, e);
            } finally {
                closeProcess(findSCU);
                Echo.shutdownService(executorService);
                Echo.shutdownService(scheduledExecutorService);
            }
        } catch (Exception e) {
            LOGGER.error("findscu", e);
            return new DicomState(Status.UnableToProcess,
                "DICOM Find failed" + StringUtil.COLON_AND_SPACE + e.getMessage(), null);
        }
    }

    private static void closeProcess(FindSCU findSCU) {
        try {
            findSCU.close();
        } catch (IOException e) {
            LOGGER.error("Closing FindSCU", e);
        } catch (InterruptedException e) {
            LOGGER.warn("Closing FindSCU Interruption"); //$NON-NLS-1$
        }
    }

    private static void forceGettingAttributes(FindSCU findSCU) {
        DicomProgress p = findSCU.getState().getProgress();
        if (p != null) {
            try {
                findSCU.close();
            } catch (Exception e) {
                // Do nothing
            }
        }
    }

    private static InformationModel getInformationModel(AdvancedParams options) {
        Object model = options.getInformationModel();
        if (model instanceof InformationModel) {
            return (InformationModel) model;
        }
        return InformationModel.StudyRoot;
    }

    public static void addAttributes(Attributes attrs, DicomParam param) {
        int tag = param.getTag();
        String[] ss = param.getValues();
        VR vr = ElementDictionary.vrOf(tag, attrs.getPrivateCreator(tag));
        if (ss == null || ss.length == 0) {
            // Returning key
            if (vr == VR.SQ) {
                attrs.newSequence(tag, 1).add(new Attributes(0));
            } else {
                attrs.setNull(tag, vr);
            }
        } else {
            // Matching key
            attrs.setString(tag, vr, ss);
        }
    }

}
