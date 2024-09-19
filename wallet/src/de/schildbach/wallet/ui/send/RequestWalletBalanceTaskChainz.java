package de.schildbach.wallet.ui.send;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import android.text.format.DateUtils;

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.UTXO;
import org.bitcoinj.script.ScriptBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.R;

/**
 * Created by Andreas Schildbach on 8/9/18.
 */

public class RequestWalletBalanceTaskChainz {
    private final Handler backgroundHandler;
    private final Handler callbackHandler;
    private final RequestWalletBalanceTask.ResultCallback resultCallback;
    @Nullable
    private final String userAgent;

    private static final Logger log = LoggerFactory.getLogger(RequestWalletBalanceTask.class);

    public RequestWalletBalanceTaskChainz(final Handler backgroundHandler, final RequestWalletBalanceTask.ResultCallback resultCallback, @Nullable final String userAgent) {
        this.backgroundHandler = backgroundHandler;
        this.callbackHandler = new Handler(Looper.myLooper());
        this.resultCallback = resultCallback;
        this.userAgent = userAgent;
    }

    public void requestWalletBalance(final ECKey... keys) {
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

                final StringBuilder url = new StringBuilder(Constants.BITEASY_API_URL);
                url.append("&key=d47da926b82e");    //Cryptoid API key
                url.append("&active=").append(keys[0].toAddress(Constants.NETWORK_PARAMETERS).toBase58());

                log.debug("trying to request wallet balance from {}", url);

                HttpURLConnection connection = null;
                Reader reader = null;

                try {
                    connection = (HttpURLConnection) new URL(url.toString()).openConnection();

                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(15 * (int)DateUtils.SECOND_IN_MILLIS);
                    connection.setReadTimeout(15 * (int)DateUtils.SECOND_IN_MILLIS);
                    connection.setUseCaches(false);
                    connection.setDoInput(true);
                    connection.setDoOutput(false);

                    connection.setRequestMethod("GET");
                    if (userAgent != null)
                        connection.addRequestProperty("User-Agent", userAgent);
                    connection.connect();

                    final int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024), Charsets.UTF_8);
                        final StringBuilder content = new StringBuilder();
                        CharStreams.copy(reader, content);

                        final JSONObject json = new JSONObject(content.toString());

                        final JSONObject jsonData = json;

                        final JSONArray jsonOutputs = jsonData.getJSONArray("unspent_outputs");

                        final Map<Sha256Hash, Transaction> transactions = new HashMap<Sha256Hash, Transaction>(jsonOutputs.length());
                        final Set<UTXO> utxos = new HashSet<UTXO>(jsonOutputs.length());

                        for (int i = 0; i < jsonOutputs.length(); i++) {
                            final JSONObject jsonOutput = jsonOutputs.getJSONObject(i);

                            final Sha256Hash utxoHash = Sha256Hash.wrap(jsonOutput.getString("tx_hash"));
                            final int utxoIndex = jsonOutput.getInt("tx_ouput_n");
                            final Coin utxoValue = Coin.valueOf(Long.parseLong(jsonOutput.getString("value")));

                            UTXO utxo = new UTXO(utxoHash, utxoIndex, utxoValue, 0, false, ScriptBuilder.createOutputScript(keys[0].toAddress(Constants.NETWORK_PARAMETERS)), keys[0].toAddress(Constants.NETWORK_PARAMETERS).toString());
                            utxos.add(utxo);
                        }

                        log.info("fetched unspent outputs from {}", url);

                        onResult(utxos);
                    } else {
                        final String responseMessage = connection.getResponseMessage();

                        log.info("got http error '{}: {}' from {}", responseCode, responseMessage, url);

                        onFail(R.string.error_http, responseCode, responseMessage);
                    }
                } catch (final JSONException x) {
                    log.info("problem parsing json from " + url, x);

                    onFail(R.string.error_parse, x.getMessage());
                } catch (final IOException x) {
                    log.info("problem querying unspent outputs from " + url, x);

                    onFail(R.string.error_io, x.getMessage());
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (final IOException x) {
                            // swallow
                        }
                    }

                    if (connection != null)
                        connection.disconnect();
                }
            }
        });
    }

    protected void onResult(final Set<UTXO> transactions) {
        callbackHandler.post(new Runnable() {
            @Override
            public void run() {
                resultCallback.onResult(transactions);
            }
        });
    }

    protected void onFail(final int messageResId, final Object... messageArgs) {
        callbackHandler.post(new Runnable() {
            @Override
            public void run() {
                resultCallback.onFail(messageResId, messageArgs);
            }
        });
    }

    private static class FakeTransaction extends Transaction {
        private final Sha256Hash hash;

        public FakeTransaction(final NetworkParameters params, final Sha256Hash hash) {
            super(params);
            this.hash = hash;
        }

        @Override
        public Sha256Hash getHash() {
            return hash;
        }
    }
}

