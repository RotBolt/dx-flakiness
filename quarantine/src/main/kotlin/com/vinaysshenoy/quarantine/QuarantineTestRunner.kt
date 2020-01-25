package com.vinaysshenoy.quarantine

import org.junit.internal.AssumptionViolatedException
import org.junit.internal.runners.model.EachTestNotifier
import org.junit.runner.Description
import org.junit.runner.notification.RunListener
import org.junit.runner.notification.RunNotifier
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement
import org.slf4j.LoggerFactory

class QuarantineTestRunner(clazz: Class<*>) : BlockJUnit4ClassRunner(clazz) {

    private val logger = LoggerFactory.getLogger(QuarantineTestRunner::class.java.simpleName)

    private val repository: TestRepository = InMemoryTestRepository()

    private val flakyTestRetryCount = 10

    override fun run(notifier: RunNotifier) {
        notifier.addListener(object : RunListener() {
            override fun testSuiteStarted(description: Description) {
                val descriptors = TestDescriptor.fromDescription(description)
                repository.add(descriptors)
                super.testSuiteStarted(description)
            }

            override fun testSuiteFinished(description: Description?) {
                super.testSuiteFinished(description)
                logger.info("Completed test run!")
                logger.info("${repository.results()}")
            }
        })
        super.run(notifier)
    }

    override fun runChild(method: FrameworkMethod, notifier: RunNotifier) {
        runChildWithFlakinessChecks(method, notifier)
    }

    private fun runChildWithFlakinessChecks(method: FrameworkMethod, notifier: RunNotifier) {
        val description = describeChild(method)
        if (isIgnored(method)) {
            notifier.fireTestIgnored(description)
        } else {
            val statement: Statement = object : Statement() {
                @Throws(Throwable::class)
                override fun evaluate() {
                    methodBlock(method).evaluate()
                }
            }

            val testClazzName = method.declaringClass.canonicalName
            val testMethodName = method.name
            runLeafWithFlakinessChecks(testClazzName, testMethodName, statement, description, notifier)
        }
    }

    private fun runLeafWithFlakinessChecks(
        testClazzName: String,
        testMethodName: String,
        statement: Statement,
        description: Description,
        notifier: RunNotifier
    ) {
        val eachNotifier = EachTestNotifier(notifier, description)
        eachNotifier.fireTestStarted()
        try {
            statement.evaluate()
            // If it passes, mark it as not-flaky
            repository.record(testClazzName, testMethodName, false)
        } catch (e: AssumptionViolatedException) {
            eachNotifier.addFailedAssumption(e)
        } catch (e: Throwable) {
            // This test having failed once need not mean that is flaky. We will try running the test some more times
            // and see if they pass in any of those. In which case, we will mark them as flaky.
            val testRunCount = generateSequence(1) { testRetryNumber ->
                logger.info("retry #$testRetryNumber for $testClazzName, $testMethodName")
                when {
                    testRetryNumber > flakyTestRetryCount -> null
                    else -> {
                        val hasPassedOnRetry = retryAndReturnException(statement) == null

                        if (hasPassedOnRetry) null else testRetryNumber + 1
                    }
                }
            }.last()

            if (testRunCount == flakyTestRetryCount) {
                // We have tested it for all retry counts and it has failed each time, so it is not a flaky test
                repository.record(testClazzName, testMethodName, false)

                // Only report failed, non-flaky tests as failures
                eachNotifier.addFailure(e)
            } else {
                repository.record(testClazzName, testMethodName, true)
            }
        } finally {
            eachNotifier.fireTestFinished()
        }
    }

    private fun retryAndReturnException(statement: Statement): Throwable? {
        var failure: Throwable? = null

        try {
            statement.evaluate()
        } catch (e: Throwable) {
            failure = e
        }

        return failure
    }
}