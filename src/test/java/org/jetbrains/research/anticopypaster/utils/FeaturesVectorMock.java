package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import org.mockito.Mock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testing class to mock a FeaturesVector given exposed feature array data.
 */
public class FeaturesVectorMock {

    @Mock
    private FeaturesVector mockFeaturesVector;

    public FeaturesVectorMock(float[] metricsArray) {
        mockFeaturesVector = mock(FeaturesVector.class);

        // mock methods for the FeaturesVector class
        when(mockFeaturesVector.buildArray())
                .thenReturn(metricsArray);
        when(mockFeaturesVector.getFeatureValue(any(Feature.class)))
                .thenAnswer(invocation -> (double) metricsArray[((Feature) invocation.getArgument(0)).getId()]);
    }

    public FeaturesVector getMock() {
        return mockFeaturesVector;
    }
}
