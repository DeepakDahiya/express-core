/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.crash;

import static org.chromium.build.NullUtil.assumeNonNull;

import android.app.job.JobParameters;
import android.app.job.JobService;

import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.browser.base.SplitCompatJobService;

/** JobScheduler entry point for retrying Browser Express JSON crash events. */
@NullMarked
public class BrowserExpressJsonEventUploadJobServiceImpl extends SplitCompatJobService.Impl {
    @Override
    public boolean onStartJob(JobParameters params) {
        JobService service = assumeNonNull(getService());
        PostTask.postTask(
                TaskTraits.BEST_EFFORT_MAY_BLOCK,
                () -> {
                    boolean reschedule =
                            BrowserExpressPendingCrashReportStore.uploadPendingEventsForJob();
                    service.jobFinished(params, reschedule);
                });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return BrowserExpressPendingCrashReportStore.hasPendingJsonEvents();
    }
}
