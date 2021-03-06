package com.musicplayer.heartmusic;

import android.util.Log;

import java.io.*;

public class BeatDetector {
    private byte bsamples[];
    private float fsamples[];

    private static final int NBANDS = 6;
    private static final int BANDLIMITS[] = {0, 100, 200, 300, 400, 600, 800, 1200, 2400};
    private static final int SPS = 22050;
    private static final int FRAME_SIZE = 1024;
    private static final int FRAME_SHIFT = 256;
    private static final int COMB_FILTER_MIN = 25;
    private static final int COMB_FILTER_MAX = 60;


    private float mBands[][];
    private float mBandDiffs[][];
    private float mCombFilterVal[];

    private float mBeatStrengths[];

    public float[] getFSamples() {
        return fsamples;
    }

    public byte[] getBSamples() {
        return bsamples;
    }

    public static float byteToFloat(byte b) {
        byte b2 = (byte) (b ^ 128);

        return ((b2 + 0.5f) / 127.5f);
    }


    public static byte floatToByte(float f) {
        byte b2 = (byte) ((f * 127.5f) - 0.5f);
        return (byte) (b2 ^ 128);
    }

    public void convertBSamples() {
        int i;
        int len = bsamples.length;
        fsamples = new float[SPS*20];

        for (i = 0; i < SPS*20; i++) {
            fsamples[i] = byteToFloat(bsamples[i + len/2]);
        }
    }

    public void convertFSamples() {
        int i;
        int len = fsamples.length;
        bsamples = new byte[len];

        for (i = 0; i < len; i++) {

            bsamples[i] = floatToByte(fsamples[i]);
        }
    }


    public void loadWavFile(String fname) {
        File file = new File(fname);
        int size = (int) file.length();
        bsamples = new byte[size];

        try {
            BufferedInputStream audioInputStream = new BufferedInputStream(new FileInputStream(file));
            audioInputStream.read(bsamples, 0, size);
            audioInputStream.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        convertBSamples();
    }

    public void calcBands() {
        int i, j, k;
        int frame;
        int fstart, fend;
        int nframes = (fsamples.length - FRAME_SIZE) / FRAME_SHIFT + 1;
        float dft[];
        int numbands = BANDLIMITS.length;

        mBands = new float[nframes][numbands];

        //Do FFT on each frame
        for (i = 0; i < nframes; i++) {
            dft = FFT.DoRealFFT(fsamples, i * FRAME_SHIFT, FRAME_SIZE);

            //zero out any DC component
            dft[0] = dft[1] = 0.0f;

            for (j = 0; j < numbands; j++) {
                fstart = (FRAME_SIZE * BANDLIMITS[j] * 2) / SPS;
                if (j == numbands - 1) {
                    fend = FRAME_SIZE;
                } else {
                    fend = (FRAME_SIZE * BANDLIMITS[j + 1] * 2) / SPS;
                }

                //add up sum of squares to get energy within that band
                mBands[i][j] = 0.0f;
                for (k = fstart; k < fend; k++) {
                    mBands[i][j] += (dft[k] * dft[k]);
                }
                for (k = (FRAME_SIZE * 2) - fstart - 1;
                     k >= (FRAME_SIZE * 2) - fend; k--) {
                    mBands[i][j] += (dft[k] * dft[k]);
                }

                //scale each band by number of frequencies...
                mBands[i][j] /= (fend - fstart);

            }
        }

    }


    //differentiates and half-wave rectifies the band info
    public void diffBands() {
        int nbands = BANDLIMITS.length;
        int nframes = mBands.length;
        int i, j;

        mBandDiffs = new float[nframes][nbands];
        for (i = 0; i < nbands; i++) {
            for (j = 1; j < nframes; j++) {
                //differentiate
                mBandDiffs[j][i] = mBands[j][i] - mBands[j - 1][i];

                //half-wave rectify
                if (mBandDiffs[j][i] < 0) {
                    mBandDiffs[j][i] = 0;
                }
            }
        }

    }


    //perform comb filtering
    //try different periods from COMB_FILTER_MIN to COMB_FILTER_MAX
    //compare sums of data taken at that interval
    public int getT(int start) {
        int i, b, T, t;
        int nbands = BANDLIMITS.length;
        int nfilters = COMB_FILTER_MAX - COMB_FILTER_MIN + 1;
        int cf;
        float cf_val[][], cf_total[];
        float cf_combined[];
        float cf_val_max;
        int T_ret = 0;
        float y;

        float sum;
        cf_val = new float[nbands][nfilters];
        cf_total = new float[nbands];
        cf_combined = new float[nfilters];


        //for each band b
        for (b = 0; b < nbands; b++) {
            //for each candidate period T
            for (T = COMB_FILTER_MIN; T <= COMB_FILTER_MAX; T++) {
                cf = T - COMB_FILTER_MIN;
                if ((start - 3 * T) >= 0) {
                    cf_val[b][cf] = 0;

                    //sum the band diff values taken at intervals of T
                    for (t = start - 3 * T; t <= start; t += T) {
                        cf_val[b][cf] += mBandDiffs[t][b];
                    }
                    cf_total[b] += cf_val[b][cf];

                }
            }

            //normalize values for each band
            for (cf = 0; cf < nfilters; cf++) {
                cf_val[b][cf] /= cf_total[b];

            }
        }



        for (cf = 0; cf < nfilters; cf++) {
            cf_combined[cf] = 0;
            for (b = 0; b < nbands; b++) {
                cf_combined[cf] += cf_val[b][cf];
            }
        }

        cf_val_max = 0;
        T_ret = 0;
        for (cf = 0; cf < nfilters; cf++) {
            if (cf_combined[cf] > cf_val_max) {
                cf_val_max = cf_combined[cf];
                T_ret = cf + COMB_FILTER_MIN;
            }
        }

        return T_ret;

    }

    //insert click at perceived onsets
    public int getBPM() {

        int i, j, k;
        boolean beat_found;
        float sum, avg;
        float bmax;
        int jmax, beat_pos;
        int T;

        int nbands = BANDLIMITS.length;
        int nframes = mBandDiffs.length;
        float beat_strength[];

        mBeatStrengths = new float[nframes];

        //sum across frequency channels to find beat candidates
        for (i = 0; i < nframes; i++) {
            sum = 0;
            for (j = 0; j < nbands; j++) {
                sum += mBandDiffs[i][j];
            }
            mBeatStrengths[i] = sum;
        }

        i = 250;
        int beat_str_len = 20;
        int start;
        //search for local maxima

        int nfilters = COMB_FILTER_MAX - COMB_FILTER_MIN + 1;
        int indexes[] = new int[nfilters];

        while (i < (nframes - beat_str_len / 2)) {

            start = i - beat_str_len / 2;
            beat_found = false;
            beat_strength = new float[beat_str_len];
            for (k = 0; k < nbands; k++) {
                //beat_found = true;
                sum = 0;
                bmax = 0;

                for (j = 0; j < beat_str_len; j++) {

                    sum += mBandDiffs[start + j][k];
                }

                for (j = 0; j < beat_str_len; j++) {
                    beat_strength[j] += mBandDiffs[start + j][k] / sum;
                }

            }

            bmax = 0;
            jmax = 0;
            for (j = 0; j < beat_str_len; j++) {
                if (beat_strength[j] > bmax) {
                    bmax = beat_strength[j];
                    jmax = j;
                }
            }

            i = start + jmax;

            T = getT(i);

            indexes[T - COMB_FILTER_MIN] ++;
            i += T;
        }

        int maxIndex = 0;
        int index = 0;

        for(int x = 0; x < nfilters; x ++) {
            if (indexes[x] > maxIndex) {
                maxIndex = indexes[x];
                index = x;
            }
        }

        return index + COMB_FILTER_MIN;
    }
}
