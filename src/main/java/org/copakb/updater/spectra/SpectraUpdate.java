package org.copakb.updater.spectra;

import org.copakb.server.dao.DAOObject;
import org.copakb.server.dao.PeptideDAO;
import org.copakb.server.dao.ProteinDAO;
import org.copakb.server.dao.model.*;
import org.copakb.updater.protein.ProteinUpdate;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Update spectra and features
 * Created by vincekyi on 6/11/15.
 */
public class SpectraUpdate {

    private final static String SPECTRUM_OUTPUT_PATH = "target/Spectra_Files/";

    private static int specNumCounter = 0;
    private static int reverseCounter = 0;

    //parameters subject to change
    public static void update(String file, int mod_id, String instr, String enzyme) {

        Date dateBeg = new Date();

        PeptideDAO peptideDAO = DAOObject.getInstance().getPeptideDAO();
        ProteinDAO proteinDAO = DAOObject.getInstance().getProteinDAO();

        LibraryModule tempLibMod = null;
        String organelle = "";
        String libmod = "";
        if (mod_id != -1) {
            tempLibMod = peptideDAO.searchLibraryModuleWithId(mod_id);
        }
        if (mod_id == -1 || tempLibMod == null) {
            String[] shortFileName = file.split("/");
            String shortName = shortFileName[shortFileName.length - 1];
            shortName = shortName.substring(0, shortName.length() - 5);
            String[] parsedShortName = shortName.split("_");
            String parseShortName = parsedShortName[parsedShortName.length - 1];

            try { // last section of file name is an integer, truncate
                organelle = parsedShortName[parsedShortName.length - 2];
                for (int i = 0; i < parsedShortName.length - 1; i++) {
                    libmod += parsedShortName[i] + "_";
                }
                libmod = libmod.substring(0, libmod.length() - 1);
            } catch (NumberFormatException e) { // last section of file name is not an integer, must be organelle
                organelle = parseShortName;
                libmod = shortName;
            } catch (ArrayIndexOutOfBoundsException e) {
                System.err.println("Filename formatted incorrectly");
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }

            String species = parsedShortName[0];
            // match to formatted species name
            species = species.substring(0, 1).toUpperCase() + species.substring(1).toLowerCase();
            Species tempSpecies = proteinDAO.searchSpecies(species);
            if (tempSpecies == null) {
                tempSpecies = new Species(0, species, null, null);
                proteinDAO.addSpecies(tempSpecies);
            }

            Date date = new Date();

            // add library module according to file name
            // assumes format as [organism]_[organ]_[organelle](_[date]).copa
            LibraryModule checkMod = peptideDAO.searchLibraryModuleWithModule(libmod);
            if (checkMod != null) {
                tempLibMod = checkMod;
            } else {
                tempLibMod = new LibraryModule(libmod, instr, organelle, date, enzyme, tempSpecies);
            }

            mod_id = peptideDAO.addLibraryModule(tempLibMod);
        }

        CopaParser cp = new CopaParser(file);
        HashMap copaEntry;
        while (cp.processEntry() >= 0) {
            copaEntry = cp.getCurrentEntry();
            populateSpectraObject(copaEntry, mod_id);
        }
        cp.closeBuffer();

        System.out.println("\n\n");
        System.out.println("BEGINNING: " + dateBeg.toString());
        Date dateEnd = new Date();
        System.out.println("ENDING: " + dateEnd.toString());

    }

    private static void populateSpectraObject(HashMap entry, int mod_id) {
        PeptideDAO peptideDAO = DAOObject.getInstance().getPeptideDAO();
        ProteinDAO proteinDAO = DAOObject.getInstance().getProteinDAO();

        System.out.println("\n\n********************");
        System.out.println("PEPID: " + entry.get("PEPID"));

        String whole_sequence = (String) entry.get("SEQ");
        String ptm_sequence = whole_sequence.substring(2, whole_sequence.length() - 2);

        ArrayList<String> variations = getVariations(ptm_sequence);
        for (String s : variations) {
            specNumCounter++;

            if (!(entry.get("REVERSE")).equals("NotReverseHit")) {
                reverseCounter++;
                continue; // skip, don't add this peptide if reverse
            }

            // populate peptide object
            Peptide peptide = new Peptide();
            String peptide_sequence = extractPeptide(ptm_sequence);
            peptide.setPeptide_sequence(peptide_sequence);
            peptide.setSequence_length(peptide_sequence.length());

            // populate spectrum object
            Spectrum spectrum = new Spectrum();
            spectrum.setPtm_sequence(s);
            int charge = Integer.parseInt((String) entry.get("CHARGE"));
            spectrum.setCharge_state(charge);
            spectrum.setXcorr(Double.parseDouble((String) entry.get("XCORR")));
            spectrum.setDelta_cn(Double.parseDouble((String) entry.get("DELTACN")));
            spectrum.setZscore(Double.parseDouble((String) entry.get("ZSCORE")));
            spectrum.setPrecursor_mz(Double.parseDouble((String) entry.get("MZ")));
            spectrum.setRawfile_id((String) entry.get("SPECTRUMFILE"));
            double fdr = ((double) reverseCounter) / ((double) specNumCounter);
            spectrum.setFdr(fdr);

            double[] arr = calcMWandPrecursor(ptm_sequence, charge);
            spectrum.setTh_precursor_mz(arr[1]);
            peptide.setMolecular_weight(arr[0]);

            spectrum.setPtm(peptideDAO.searchPtmType(parsePtmSequence(ptm_sequence)));

            LibraryModule tempLibMod = peptideDAO.searchLibraryModuleWithId(mod_id);
            spectrum.setModule(tempLibMod);

            // temp values
            Peptide tempPep = peptideDAO.searchBySequence(ptm_sequence);
            if (tempPep == null) {
                String seq = ptm_sequence.replaceAll("[^A-Za-z]", "");
                tempPep = new Peptide(ptm_sequence, calcMWandPrecursor(ptm_sequence, 1)[0], seq.length());
                peptideDAO.addPeptide(tempPep);
            }

            spectrum.setPeptide(tempPep);
            int specNum;

            // before adding, check if spectrum exists already
            List<Spectrum> dbSpectrums = peptideDAO.searchSpectrum(ptm_sequence, mod_id, charge);
            if (dbSpectrums == null || dbSpectrums.isEmpty()) {
                // not yet in database or if different values in database
                specNum = peptideDAO.addSpectrum(spectrum);
            } else {
                specNum = dbSpectrums.get(0).getSpectrum_id();
            }

            // create and save spectrum files; currently hardcoded the location
            //noinspection ResultOfMethodCallIgnored
            new File(SPECTRUM_OUTPUT_PATH).mkdir();
            String fileName = SPECTRUM_OUTPUT_PATH + specNum + ".txt";
            try {
                BufferedWriter writer = new BufferedWriter(new FileWriter(new File(fileName)));
                writer.write(entry.get("header") + "\n");
                writer.write(entry.get("spectrum").toString());
                writer.close();
            } catch (Exception e) {
                e.printStackTrace();
            }

            // Spectrum Protein
            SpectrumProtein sp = new SpectrumProtein();
            sp.setPrevAA(whole_sequence.charAt(0));
            sp.setNextAA(whole_sequence.charAt(whole_sequence.length() - 1));
            String proteins = entry.get("UNIPROTIDS").toString();

            String[] tokens = proteins.split(";");

            // todo: spectrum protein update testing to be done

            // check referenced proteins in copa files and if relevant spectrum protein
            // objects exist in the database
            ArrayList<String> tokensInFile = new ArrayList<>(Arrays.asList(tokens));

            Spectrum tempSpecInDb = peptideDAO.searchSpectrum(ptm_sequence, mod_id, charge).get(0);
            List<SpectrumProtein> tempTokensInDb = proteinDAO.searchSpectrumProteins(tempSpecInDb);

            List<String> tokensToAdd;
            List<String> tokensToDelete = null;

            if (tempTokensInDb != null) { // if there are spectrum proteins in the database, check if they need to be deleted
                // convert matching spectrums to just get the list of uniprot ids
                ArrayList<String> tokensDelete = new ArrayList<>();
                tempTokensInDb.forEach((SpectrumProtein spectrumProtein) ->
                        tokensDelete.add(spectrumProtein.getProtein().getProtein_acc()));
                List<String> tokensInDb = new ArrayList<>(tokensDelete);

                tokensToAdd = new ArrayList<>(tokensInFile);
                tokensToDelete = new ArrayList<>(tokensInDb);

                tokensToAdd.removeAll(tokensInDb);
                tokensToDelete.removeAll(tokensInFile);
            } else { // if nothing in database, then just add all uniprot ids
                tokensToAdd = new ArrayList<>(Arrays.asList(tokens));
            }

            // add the spectrum proteins objects for each protein referenced (but not yet added)
            ProteinCurrent prot;
            String protSeq;
            int loc;

            for (String token : tokensToAdd) {
                prot = proteinDAO.searchByID(token);
                if (prot == null) {
                    try {
                        // Get and add protein if not in database
                        prot = ProteinUpdate.getProteinFromUniprot(token);
                        if (prot != null) {
                            proteinDAO.addProteinCurrent(prot);
                        }
                    } catch (IOException | ParserConfigurationException | SAXException e) {
                        e.printStackTrace();
                    }
                }

                if (prot == null) {
                    System.out.println("Could not add SpectrumProtein: " + token);
                    continue;
                }
                protSeq = prot.getSequence();

                String tempPtmSeq = ptm_sequence.replaceAll("[^A-Za-z]", "");
                loc = protSeq.indexOf(tempPtmSeq);

                sp.setProtein(proteinDAO.searchByID(prot.getProtein_acc()));
                sp.setLocation(loc);
                sp.setLibraryModule(tempLibMod);
                sp.setFeature_peptide(true);
                sp.setSpecies_unique(true);
                Spectrum tempSpec = peptideDAO.searchSpectrum(
                        spectrum.getPtm_sequence(), spectrum.getModule().getMod_id(), spectrum.getCharge_state())
                        .get(0);
                sp.setSpectrum(tempSpec);
                sp.setPeptide(tempPep);
                proteinDAO.addSpectrumProtein(sp);
                System.out.println("Added: " + sp.getProtein().getProtein_acc());
            }

            if (tokensToDelete == null)
                continue;

            // delete all spectrum protein objects that are in database but no longer in COPA file
            for (String token : tokensToDelete) {

                // get persistent entities to be able to search & delete
                ProteinCurrent existingProtein = proteinDAO.searchByID(token);
                List<Spectrum> spectrums = peptideDAO.searchSpectrum(spectrum.getPtm_sequence(),
                        spectrum.getModule().getMod_id(), spectrum.getCharge_state());

                spectrum = spectrums.get(0);

                // get latest version
                Version version = new Version();
                version.setVersion(proteinDAO.searchLatestVersion().getVersion() - 1); // set it to previous versions
                version.setDescription("Update"); // todo: change the description values
                version.setDate(new Date());

                SpectrumProtein spectrumProtein = proteinDAO.searchSpectrumProtein(spectrum, existingProtein);
                SpectrumProteinHistory spectrumProteinHistory = new SpectrumProteinHistory();
                spectrumProteinHistory.setSpectrumProtein_id(spectrumProtein.getSpectrumProtein_id());
                spectrumProteinHistory.setSpectrum_id(spectrumProtein.getSpectrum().getSpectrum_id());
                spectrumProteinHistory.setVersion(version);
                spectrumProteinHistory.setProtein_acc(spectrumProtein.getProtein().getProtein_acc());
                spectrumProteinHistory.setFeature_peptide(spectrumProtein.isFeature_peptide());
                spectrumProteinHistory.setSpecies_unique(spectrumProtein.isSpecies_unique());
                spectrumProteinHistory.setLibraryModule(spectrumProtein.getLibraryModule().getMod_id());
                spectrumProteinHistory.setLocation(spectrumProtein.getLocation());
                spectrumProteinHistory.setPrevAA(spectrumProtein.getPrevAA());
                spectrumProteinHistory.setNextAA(spectrumProtein.getNextAA());
                spectrumProteinHistory.setDelete_date(new Date());

                // add to spectrum protein history, then delete
                proteinDAO.addSpectrumProteinHistory(spectrumProteinHistory);
                proteinDAO.deleteSpectrumProtein(spectrumProtein.getSpectrumProtein_id());
                System.out.println("Deleted: " + spectrumProtein.getProtein().getProtein_acc());
            }
        }
    }

    public static void updateUniqueStates() {
        PeptideDAO peptideDAO = DAOObject.getInstance().getPeptideDAO();

        // iterate through every peptide and looking at the associated set of spectra
        for (Peptide peptide : peptideDAO.list()) {
            // arraylist of arraylist of peptides within that species
            // each contained list is relevant to a species, where index = species id
            System.out.println("Processing: " + peptide.getPeptide_id());
            List<Spectrum> pepSpectrums = peptideDAO.searchSpectrum(peptide.getPeptide_sequence(), -1, -1);
            if (pepSpectrums == null || pepSpectrums.isEmpty()) {
                System.out.println("\tSkipping " + peptide.getPeptide_id());
                continue;
            }

            HashMap<Integer, ArrayList<Spectrum>> sortedPeptides = new HashMap<>();
            for (int i = 0; i <= 10; i++) { // todo: assuming 10 species, fix; could reuse for every peptide
                // initialize the species keys
                if (peptideDAO.searchSpecies(i) != null) {
                    sortedPeptides.put(i, new ArrayList<>());
                }
            }

            for (Spectrum spectrum : pepSpectrums) { // sort
                int species_id = spectrum.getModule().getSpecies().getSpecies_id();
                sortedPeptides.get(species_id).add(spectrum);
            }

            sortedPeptides.values().stream().filter(tempArr -> tempArr.size() == 1).forEach(tempArr -> {
                Spectrum spec = tempArr.get(0);
                spec.setSpecies_unique(true);
                peptideDAO.updateSpectrumSpecies(spec.getSpectrum_id(), spec);
                System.out.println("Species unique: " + spec.getSpectrum_id());
            });

        }
    }

    public static void updateFeatureStates() {
        PeptideDAO peptideDAO = DAOObject.getInstance().getPeptideDAO();

        List<Peptide> peptides = peptideDAO.list();
        for (Peptide peptide : peptides) {
            System.out.println("Processing: " + peptide.getPeptide_id());
            List<Spectrum> pepSpectrums = peptideDAO.searchSpectrum(peptide.getPeptide_sequence(), -1, -1);
            if (pepSpectrums == null || pepSpectrums.size() == 0) {
                System.out.println("\tSkipping " + peptide.getPeptide_id());
                continue;
            }

            // filter through charges for each
            for (int i = 0; i < 5; i++) { // todo: random range of 0-5 charge
                List<Spectrum> pepChargeSpectrums = peptideDAO.searchSpectrum(peptide.getPeptide_sequence(), -1, i);
                // find all the spectrum for a certain sequence and charge
                if (pepChargeSpectrums != null) { // list of all spectra with certain sequence and charge
                    // if only one of sequence + charge combo, then it is unique to one module
                    // (key id for spectrum is seq + charge + mod)
                    if (pepChargeSpectrums.size() == 1) {
                        Spectrum spec = pepChargeSpectrums.get(0);
                        spec.setFeature_peptide(true);
                        peptideDAO.updateSpectrumFeature(spec.getSpectrum_id(), spec);
                        System.out.println("Feature peptide: " + spec.getSpectrum_id());
                    }
                }
            }
        }
    }

    private static ArrayList<String> getVariations(String sequence) {

        ArrayList<String> variations = new ArrayList<>();
        variations.add(sequence);
        if (sequence.contains("Z") || sequence.contains("B") || sequence.contains("J")) {
            // if Z is found
            for (int i = sequence.indexOf("Z", 0); i >= 0; i = sequence.indexOf("Z", i + 1)) {
                int len = variations.size();
                for (int j = 0; j < len; j++) {
                    variations.add(variations.get(j).substring(0, i) + "E" + variations.get(j).substring(i + 1));
                    variations.add(variations.get(j).substring(0, i) + "Q" + variations.get(j).substring(i + 1));
                    variations.remove(j);
                    j--;
                    len--;
                }
            }
            // if B is found
            for (int i = sequence.indexOf("B", 0); i >= 0; i = sequence.indexOf("B", i + 1)) {
                int len = variations.size();
                for (int j = 0; j < len; j++) {
                    variations.add(variations.get(j).substring(0, i) + "D" + variations.get(j).substring(i + 1));
                    variations.add(variations.get(j).substring(0, i) + "N" + variations.get(j).substring(i + 1));
                    variations.remove(j);
                    j--;
                    len--;
                }
            }
            // if J is found
            for (int i = sequence.indexOf("J", 0); i >= 0; i = sequence.indexOf("J", i + 1)) {
                int len = variations.size();
                for (int j = 0; j < len; j++) {
                    variations.add(variations.get(j).substring(0, i) + "L" + variations.get(j).substring(i + 1));
                    variations.add(variations.get(j).substring(0, i) + "I" + variations.get(j).substring(i + 1));
                    variations.remove(j);
                    j--;
                    len--;
                }
            }
        }

        return variations;

    }

    private static String extractPeptide(String ptm_sequence) {
        String peptide = "";
        int i, start = 0, end;
        int len = ptm_sequence.length();
        for (i = 0; i < len; i++) {
            // look for open parentheses
            if (ptm_sequence.charAt(i) == '(') {
                end = i;
                peptide += ptm_sequence.substring(start, end);
                // skip to end parentheses
                int j = i;
                while (j < len) {
                    if (ptm_sequence.charAt(j) == ')') {
                        break;
                    }
                    j++;
                }
                i = j;
                start = j + 1;
            }
        }

        if (start < len) {
            peptide += ptm_sequence.substring(start, len);
        }

        return peptide;
    }

    private static double[] calcMWandPrecursor(String sequence, double charge) {

        double result[] = {0, 0};
        // initialize molecular weight and theoretical precursor
        double MW = 0, th_mz = 0;

        // holds the sequence so it can be modified
        StringBuilder buffer = new StringBuilder(sequence);
        int length = buffer.length();

        // loop through each character and add to molecular weight depending on amino acid
        for (int i = 0; i < length; i++) {
            char c = sequence.charAt(i);
            switch (c) {
                case 'A':
                    MW += 71.03711;
                    th_mz += 71.03711;
                    break;
                case 'C':
                    MW += 103.00919;
                    th_mz += 103.00919;
                    //buffer.insert(i+1, "(57.02146)");
                    break;
                case 'D':
                    MW += 115.02694;
                    th_mz += 115.02694;
                    break;
                case 'E':
                    MW += 129.04259;
                    th_mz += 129.04259;
                    break;
                case 'F':
                    MW += 147.06841;
                    th_mz += 147.06841;
                    break;
                case 'G':
                    MW += 57.02146;
                    th_mz += 57.02146;
                    break;
                case 'H':
                    MW += 137.05891;
                    th_mz += 137.05891;
                    break;
                case 'I':
                    MW += 113.08406;
                    th_mz += 113.08406;
                    break;
                case 'K':
                    MW += 128.09496;
                    th_mz += 128.09496;
                    break;
                case 'L':
                    MW += 113.08406;
                    th_mz += 113.08406;
                    break;
                case 'M':
                    MW += 131.04048;
                    th_mz += 131.04048;
                    break;
                case 'N':
                    MW += 114.04293;
                    th_mz += 114.04293;
                    break;
                case 'P':
                    MW += 97.05276;
                    th_mz += 97.05276;
                    break;
                case 'Q':
                    MW += 128.05858;
                    th_mz += 128.05858;
                    break;
                case 'R':
                    MW += 156.10111;
                    th_mz += 156.10111;
                    break;
                case 'S':
                    MW += 87.03202;
                    th_mz += 87.03202;
                    break;
                case 'T':
                    MW += 101.04768;
                    th_mz += 101.04768;
                    break;
                case 'V':
                    MW += 99.06841;
                    th_mz += 99.06841;
                    break;
                case 'W':
                    MW += 186.07931;
                    th_mz += 186.07931;
                    break;
                case 'Y':
                    MW += 163.06333;
                    th_mz += 163.06333;
                    break;
                case '(':
                    int end = buffer.indexOf(")", i);
                    String value = buffer.substring(i + 1, end);
                    try {
                        th_mz += Double.parseDouble(value);
                    } catch (NumberFormatException ex) {
                        //do nothing
                    }
                    break;
                default:
                    break;
            }//end switch
        }//end for loop

        // return molecular weight
        result[0] = MW + 18.0152;

        // return the (molecular weight + weight of water + charge)/charge
        result[1] = (th_mz + 18.0152 + charge) / charge;
        //System.out.println(result[1]);
        return result;
    }

    //    1	Carbamidomethylation	C,K,H	57.02000
//    2	Acetylation	K,N-term	42.01000
//    4	Oxidation	M	15.99000
//    8	Phosphorylation	S,T	79.97000
//    16	Succinylation	K	100.01860
//    32	Propionamide	C	71.03712
//    64	Pyro-carbamidomethyl	C	39.99492
//    128	Pyro-glu	E	-17.03000
    private static int parsePtmSequence(String ptm_sequence) {
        int result = 0;
        double range = 0.01;

        boolean carb = false;
        boolean acet = false;
        boolean oxid = false;
        boolean phos = false;
        boolean succ = false;
        boolean prop = false;
        boolean pyroc = false;
        boolean pyrog = false;

        String tempPtmType = "";
        for (char aa : ptm_sequence.toCharArray()) {
            if (aa == '(') {
                tempPtmType = "";
            } else if (aa == ')' && tempPtmType.length() > 1) {
                // At end of sequence, check the PTM type based on the value and add to total type number
                double ptmVal = Double.parseDouble(tempPtmType);
                System.out.println(ptmVal);
                if (!carb && withinRange(ptmVal, 57.020000, range)) { // Carbamidomethylation
                    result += 1;
                    carb = true;
                } else if (!acet && withinRange(ptmVal, 42.01000, range)) { // Acetylation
                    result += 2;
                    acet = true;
                } else if (!oxid && withinRange(ptmVal, 15.99000, range)) { // Oxidation
                    result += 4;
                    oxid = true;
                } else if (!phos && withinRange(ptmVal, 79.97000, range)) { // Phosphorylation
                    result += 8;
                    phos = true;
                } else if (!succ && withinRange(ptmVal, 100.01860, range)) { // Succinylation
                    result += 16;
                    succ = true;
                } else if (!prop && withinRange(ptmVal, 71.03712, range)) { // Propionamide
                    result += 32;
                    prop = true;
                } else if (!pyroc && withinRange(ptmVal, 39.99492, range)) { // Pyro-carbamidomethyl
                    result += 64;
                    pyroc = true;
                } else if (!pyrog && withinRange(ptmVal, -17.03000, range)) { // Pyro-glu
                    result += 128;
                    pyrog = true;
                }
            } else if (Character.isDigit(aa) || aa == '.') {
                // Add PTM number together
                tempPtmType += aa;
            }
        }

        return result;
    }

    public static boolean withinRange(double num, double refNum, double range) {
        return (num <= (refNum + range)) && (num >= (refNum - range));
    }
}
