package com.dotmarketing.quartz.job;

import com.dotcms.business.CloseDBIfOpened;
import com.dotcms.integritycheckers.IntegrityUtil;
import com.dotcms.publisher.endpoint.bean.PublishingEndPoint;
import com.dotcms.rest.IntegrityResource;
import com.dotmarketing.db.HibernateUtil;
import com.dotmarketing.quartz.DotStatefulJob;
import com.dotmarketing.quartz.QuartzUtils;
import com.dotmarketing.util.Logger;
import com.rainerhahnekamp.sneakythrow.Sneaky;
import org.quartz.InterruptableJob;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.UnableToInterruptJobException;

import java.util.Date;

public class IntegrityDataGenerationJob extends DotStatefulJob implements InterruptableJob {

    private static final String TRIGGER_NAME = "IntegrityDataGenerationTrigger";
    private static final String TRIGGER_GROUP = "integrity_data_generation_triggers";

    public static final String JOB_NAME = "IntegrityDataGenerationJob";
    public static final String JOB_GROUP_NAME = "integrity_data_generation_jobs";

    private JobExecutionContext jobContext;

    @Override
    @CloseDBIfOpened
    public void run(final JobExecutionContext jobContext) throws JobExecutionException {
        this.jobContext = jobContext;
        final JobDataMap jobDataMap = this.jobContext.getJobDetail().getJobDataMap();
        final PublishingEndPoint requesterEndPoint =
                (PublishingEndPoint) jobDataMap.get(IntegrityUtil.REQUESTER_ENDPOINT);
        final String requestId = (String) jobDataMap.get(IntegrityUtil.INTEGRITY_DATA_REQUEST_ID);

        IntegrityUtil.saveIntegrityDataStatus(
                requesterEndPoint.getId(),
                requestId,
                IntegrityResource.ProcessStatus.PROCESSING);

        try {
            // Actual integrity data file generation
            IntegrityUtil.generateDataToCheckZip(requesterEndPoint.getId());
            // Integrity data generation went ok
            IntegrityUtil.saveIntegrityDataStatus(
                    requesterEndPoint.getId(),
                    requestId,
                    IntegrityResource.ProcessStatus.FINISHED);
            Logger.info(
                    IntegrityDataGenerationJob.class,
                    String.format("Job execution for endpoint %s has finished", requesterEndPoint.getId()));
        } catch (Exception e) {
            // Error has happened while generating integrity data
            Logger.error(IntegrityDataGenerationJob.class, "Error generating data to check", e);
            IntegrityUtil.saveIntegrityDataStatus(
                    requesterEndPoint.getId(),
                    requestId,
                    IntegrityResource.ProcessStatus.ERROR,
                    String.format("Error generating data to check: %s", e.getMessage()));
        }
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        if (jobContext == null) {
            throw new UnableToInterruptJobException(String.format("Could not find a job detail for %s", JOB_NAME));
        }

        Logger.debug(
                IntegrityDataGenerationJob.class,
                "Requested interruption of generation of data to check by the user");
        final JobDataMap jobDataMap = this.jobContext.getJobDetail().getJobDataMap();
        final PublishingEndPoint requesterEndPoint =
                (PublishingEndPoint) jobDataMap.get(IntegrityUtil.REQUESTER_ENDPOINT);
        final String requestId = (String) jobDataMap.get(IntegrityUtil.INTEGRITY_DATA_REQUEST_ID);

        IntegrityUtil.saveIntegrityDataStatus(
                requesterEndPoint.getId(),
                requestId,
                IntegrityResource.ProcessStatus.CANCELLED);
    }

    public static void triggerIntegrityDataGeneration(final PublishingEndPoint requesterEndPoint,
                                                      final String integrityDataRequestId) {
        final JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(IntegrityUtil.INTEGRITY_DATA_REQUEST_ID, integrityDataRequestId);
        jobDataMap.put(IntegrityUtil.REQUESTER_ENDPOINT, requesterEndPoint);

        final JobDetail jd = new JobDetail(JOB_NAME, JOB_GROUP_NAME, IntegrityDataGenerationJob.class);
        jd.setJobDataMap(jobDataMap);
        jd.setDurability(false);
        jd.setVolatility(false);
        jd.setRequestsRecovery(true);

        final SimpleTrigger trigger = new SimpleTrigger(
                TRIGGER_NAME,
                TRIGGER_GROUP,
                new Date(System.currentTimeMillis()));
        HibernateUtil.addCommitListenerNoThrow(Sneaky.sneaked(() -> {
           getJobScheduler().scheduleJob(jd, trigger);
        }));
    }

    public static Scheduler getJobScheduler() throws SchedulerException {
        return QuartzUtils.getStandardScheduler();
    }

}
