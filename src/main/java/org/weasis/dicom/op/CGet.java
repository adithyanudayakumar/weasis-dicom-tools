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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.dcm4che3.net.Connection;
import org.dcm4che3.net.QueryOption;
import org.dcm4che3.net.Status;
import org.dcm4che3.tool.common.CLIUtils;
import org.dcm4che3.tool.getscu.GetSCU;
import org.dcm4che3.tool.getscu.GetSCU.InformationModel;
import org.dcm4che3.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.param.AdvancedParams;
import org.weasis.dicom.param.DicomNode;
import org.weasis.dicom.param.DicomParam;
import org.weasis.dicom.param.DicomProgress;
import org.weasis.dicom.param.DicomState;
import org.weasis.dicom.util.StringUtil;

public class CGet {

    private static final Logger LOGGER = LoggerFactory.getLogger(CGet.class);

    private CGet() {
    }

    /**
     * @param callingNode
     *            the calling DICOM node configuration
     * @param calledNode
     *            the called DICOM node configuration
     * @param progress
     *            the progress handler
     * @param keys
     *            the matching and returning keys. DicomParam with no value is a returning key.
     * @return The DicomSate instance which contains the DICOM response, the DICOM status, the error message and the
     *         progression.
     */
    public static DicomState process(DicomNode callingNode, DicomNode calledNode, DicomProgress progress,
        File outputDir, DicomParam... keys) {
        return process(null, callingNode, calledNode, progress, outputDir, keys);
    }

    /**
     * @param params
     *            optional advanced parameters (proxy, authentication, connection and TLS)
     * @param callingNode
     *            the calling DICOM node configuration
     * @param calledNode
     *            the called DICOM node configuration
     * @param progress
     *            the progress handler
     * @param keys
     *            the matching and returning keys. DicomParam with no value is a returning key.
     * @return The DicomSate instance which contains the DICOM response, the DICOM status, the error message and the
     *         progression.
     */
    public static DicomState process(AdvancedParams params, DicomNode callingNode, DicomNode calledNode,
        DicomProgress progress, File outputDir, DicomParam... keys) {
        return process(params, callingNode, calledNode, progress, outputDir, null, keys);
    }

    /**
     * @param params
     *            optional advanced parameters (proxy, authentication, connection and TLS)
     * @param callingNode
     *            the calling DICOM node configuration
     * @param calledNode
     *            the called DICOM node configuration
     * @param progress
     *            the progress handler
     * @param keys
     *            the matching and returning keys. DicomParam with no value is a returning key.
     * @return The DicomSate instance which contains the DICOM response, the DICOM status, the error message and the
     *         progression.
     */
    public static DicomState process(AdvancedParams params, DicomNode callingNode, DicomNode calledNode,
        DicomProgress progress, File outputDir, URL sopClassURL, DicomParam... keys) {
        if (callingNode == null || calledNode == null || outputDir == null) {
            throw new IllegalArgumentException("callingNode, calledNode or outputDir cannot be null!");
        }
        GetSCU getSCU = null;
        AdvancedParams options = params == null ? new AdvancedParams() : params;

        try {
            getSCU = new GetSCU(progress);
            Connection remote = getSCU.getRemoteConnection();
            Connection conn = getSCU.getConnection();
            options.configureConnect(getSCU.getAAssociateRQ(), remote, calledNode);
            options.configureBind(getSCU.getApplicationEntity(), conn, callingNode);

            // configure
            options.configure(conn);
            options.configureTLS(conn, remote);

            getSCU.setPriority(options.getPriority());

            getSCU.setStorageDirectory(outputDir);

            getSCU.setInformationModel(getInformationModel(options), options.getTsuidOrder(),
                options.getQueryOptions().contains(QueryOption.RELATIONAL));

            configureRelatedSOPClass(getSCU, sopClassURL);

            for (DicomParam p : keys) {
                getSCU.addKey(p.getTag(), p.getValues());
            }

            ExecutorService executorService = Executors.newSingleThreadExecutor();
            ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
            getSCU.getDevice().setExecutor(executorService);
            getSCU.getDevice().setScheduledExecutor(scheduledExecutorService);
            try {
                DicomState dcmState = getSCU.getState();
                long t1 = System.currentTimeMillis();
                getSCU.open();
                getSCU.retrieve();
                long t2 = System.currentTimeMillis();
                String timeMsg = MessageFormat.format("Get files from {0} to {1} in {2}ms",
                    getSCU.getAAssociateRQ().getCallingAET(), getSCU.getAAssociateRQ().getCalledAET(), t2 - t1);
                forceGettingAttributes(getSCU);
                return DicomState.buildMessage(dcmState, timeMsg, null);
            } catch (Exception e) {
                LOGGER.error("getscu", e);
                forceGettingAttributes(getSCU);
                return DicomState.buildMessage(getSCU.getState(), null, e);
            } finally {
                closeProcess(getSCU);
                Echo.shutdownService(executorService);
                Echo.shutdownService(scheduledExecutorService);
            }
        } catch (Exception e) {
            LOGGER.error("getscu", e);
            return new DicomState(Status.UnableToProcess,
                "DICOM Get failed" + StringUtil.COLON_AND_SPACE + e.getMessage(), null);
        }
    }
    
    private static void closeProcess(GetSCU getSCU) {
        try {
            getSCU.close();
        } catch (IOException e) {
            LOGGER.error("Closing GetSCU", e);
        } catch (InterruptedException e) {
            LOGGER.warn("Closing GetSCU Interruption"); //$NON-NLS-1$
        }
    }

    private static void forceGettingAttributes(GetSCU getSCU) {
        DicomProgress p = getSCU.getState().getProgress();
        if (p != null) {
            try {
                getSCU.close();
            } catch (Exception e) {
                // Do nothing
            }
        }
    }

    private static void configureRelatedSOPClass(GetSCU getSCU, URL url) throws IOException {
        Properties p = new Properties();
        try {
            if (url == null) {
                p.load(getSCU.getClass().getResourceAsStream("store-tcs.properties"));
            } else {
                p.load(url.openStream());
            }
            for (Entry<Object, Object> entry : p.entrySet()) {
                configureStorageSOPClass(getSCU, (String) entry.getKey(), (String) entry.getValue());
            }
        } catch (Exception e) {
            LOGGER.error("Read sop classes", e);
        }
    }

    private static void configureStorageSOPClass(GetSCU getSCU, String cuid, String tsuids) {
        String[] ts = StringUtils.split(tsuids, ';');
        for (int i = 0; i < ts.length; i++) {
            ts[i] = CLIUtils.toUID(ts[i]);
        }
        getSCU.addOfferedStorageSOPClass(CLIUtils.toUID(cuid), ts);
    }

    private static InformationModel getInformationModel(AdvancedParams options) {
        Object model = options.getInformationModel();
        if (model instanceof InformationModel) {
            return (InformationModel) model;
        }
        return InformationModel.StudyRoot;
    }

}
